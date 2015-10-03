/*
 * Copyright 2011-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.fat;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.core.config.ConfigService;
import org.glowroot.agent.core.config.ConfigService.ShadeProtectedTypeReference;
import org.glowroot.common.config.AdvancedConfig;
import org.glowroot.common.config.GaugeConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.PluginConfig;
import org.glowroot.common.config.PluginDescriptor;
import org.glowroot.common.config.TransactionConfig;
import org.glowroot.common.config.UserRecordingConfig;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.ImmutableRollupConfig;
import org.glowroot.storage.repo.config.AlertConfig;
import org.glowroot.storage.repo.config.ImmutableAlertConfig;
import org.glowroot.storage.repo.config.ImmutableSmtpConfig;
import org.glowroot.storage.repo.config.ImmutableStorageConfig;
import org.glowroot.storage.repo.config.ImmutableUserInterfaceConfig;
import org.glowroot.storage.repo.config.SmtpConfig;
import org.glowroot.storage.repo.config.StorageConfig;
import org.glowroot.storage.repo.config.UserInterfaceConfig;
import org.glowroot.storage.util.Encryption;

import static com.google.common.base.Preconditions.checkState;

class ConfigRepositoryImpl implements ConfigRepository {

    private static final Logger logger = LoggerFactory.getLogger(ConfigRepositoryImpl.class);

    // 1 minute
    private static final long ROLLUP_0_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.0.intervalMillis", 60 * 1000);
    // 5 minutes
    private static final long ROLLUP_1_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.1.intervalMillis", 5 * 60 * 1000);
    // 30 minutes
    private static final long ROLLUP_2_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.rollup.2.intervalMillis", 30 * 60 * 1000);

    private final ImmutableList<PluginDescriptor> pluginDescriptors;

    private final ConfigService configService;
    private final File secretFile;

    private final Object writeLock = new Object();

    private final ImmutableList<RollupConfig> rollupConfigs;

    private volatile UserInterfaceConfig userInterfaceConfig;
    private volatile StorageConfig storageConfig;
    private volatile SmtpConfig smtpConfig;
    private volatile ImmutableList<AlertConfig> alertConfigs;

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();

    // volatile not needed as access is guarded by secretFile
    private @MonotonicNonNull SecretKey secretKey;

    static ConfigRepository create(File baseDir, List<PluginDescriptor> pluginDescriptors,
            ConfigService configService) {
        ConfigRepositoryImpl configRepository =
                new ConfigRepositoryImpl(baseDir, pluginDescriptors, configService);
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        try {
            configRepository.writeAll();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return configRepository;
    }

    private ConfigRepositoryImpl(File baseDir, List<PluginDescriptor> pluginDescriptors,
            ConfigService configService) {
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        this.configService = configService;
        secretFile = new File(baseDir, "secret");

        rollupConfigs = ImmutableList.<RollupConfig>of(
                // default rollup level #0 fixed interval is 1 minute,
                // making default view threshold 15 min
                ImmutableRollupConfig.of(ROLLUP_0_INTERVAL_MILLIS, ROLLUP_0_INTERVAL_MILLIS * 15),
                // default rollup level #1 fixed interval is 5 minutes,
                // making default view threshold 1 hour
                ImmutableRollupConfig.of(ROLLUP_1_INTERVAL_MILLIS, ROLLUP_1_INTERVAL_MILLIS * 12),
                // default rollup level #2 fixed interval is 30 minutes,
                // making default view threshold 8 hour
                ImmutableRollupConfig.of(ROLLUP_2_INTERVAL_MILLIS, ROLLUP_2_INTERVAL_MILLIS * 16));

        UserInterfaceConfig userInterfaceConfig =
                configService.getOtherConfig("ui", ImmutableUserInterfaceConfig.class);
        if (userInterfaceConfig == null) {
            this.userInterfaceConfig = ImmutableUserInterfaceConfig.builder().build();
        } else {
            this.userInterfaceConfig = userInterfaceConfig;
        }
        StorageConfig storageConfig =
                configService.getOtherConfig("storage", ImmutableStorageConfig.class);
        if (storageConfig == null) {
            this.storageConfig = ImmutableStorageConfig.builder().build();
        } else if (storageConfig.hasListIssues()) {
            this.storageConfig = withCorrectedLists(storageConfig);
        } else {
            this.storageConfig = storageConfig;
        }
        SmtpConfig smtpConfig = configService.getOtherConfig("smtp", ImmutableSmtpConfig.class);
        if (smtpConfig == null) {
            this.smtpConfig = ImmutableSmtpConfig.builder().build();
        } else {
            this.smtpConfig = smtpConfig;
        }
        List<ImmutableAlertConfig> alertConfigs = configService.getOtherConfig("alerts",
                new ShadeProtectedTypeReference<List<ImmutableAlertConfig>>() {});
        if (alertConfigs == null) {
            this.alertConfigs = ImmutableList.of();
        } else {
            this.alertConfigs = ImmutableList.<AlertConfig>copyOf(alertConfigs);
        }
    }

    @Override
    public TransactionConfig getTransactionConfig(String server) {
        return configService.getTransactionConfig();
    }

    @Override
    public UserRecordingConfig getUserRecordingConfig(String server) {
        return configService.getUserRecordingConfig();
    }

    @Override
    public AdvancedConfig getAdvancedConfig(String server) {
        return configService.getAdvancedConfig();
    }

    @Override
    public @Nullable PluginConfig getPluginConfig(String server, String pluginId) {
        return configService.getPluginConfig(pluginId);
    }

    @Override
    public List<InstrumentationConfig> getInstrumentationConfigs(String server) {
        return configService.getInstrumentationConfigs();
    }

    @Override
    public @Nullable InstrumentationConfig getInstrumentationConfig(String server,
            String version) {
        for (InstrumentationConfig instrumentationConfig : configService
                .getInstrumentationConfigs()) {
            if (instrumentationConfig.version().equals(version)) {
                return instrumentationConfig;
            }
        }
        return null;
    }

    @Override
    public List<GaugeConfig> getGaugeConfigs(String server) {
        return configService.getGaugeConfigs();
    }

    @Override
    public @Nullable GaugeConfig getGaugeConfig(String server, String version) {
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            if (gaugeConfig.version().equals(version)) {
                return gaugeConfig;
            }
        }
        return null;
    }

    @Override
    public List<AlertConfig> getAlertConfigs(String server) {
        return alertConfigs;
    }

    @Override
    public @Nullable AlertConfig getAlertConfig(String server, String version) {
        for (AlertConfig alertConfig : alertConfigs) {
            if (alertConfig.version().equals(version)) {
                return alertConfig;
            }
        }
        return null;
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() {
        return userInterfaceConfig;
    }

    @Override
    public StorageConfig getStorageConfig() {
        return storageConfig;
    }

    @Override
    public SmtpConfig getSmtpConfig() {
        return smtpConfig;
    }

    @Override
    public String updateTransactionConfig(String server, TransactionConfig updatedConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(configService.getTransactionConfig().version(), priorVersion);
            configService.updateTransactionConfig(updatedConfig);
        }
        configService.notifyConfigListeners();
        return updatedConfig.version();
    }

    @Override
    public String updateUserRecordingConfig(String server,
            UserRecordingConfig userRecordingConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(configService.getUserRecordingConfig().version(), priorVersion);
            configService.updateUserRecordingConfig(userRecordingConfig);
        }
        configService.notifyConfigListeners();
        return userRecordingConfig.version();
    }

    @Override
    public String updateAdvancedConfig(String server, AdvancedConfig advancedConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(configService.getAdvancedConfig().version(), priorVersion);
            configService.updateAdvancedConfig(advancedConfig);
        }
        configService.notifyConfigListeners();
        return advancedConfig.version();
    }

    @Override
    public String updatePluginConfig(String server, PluginConfig pluginConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<PluginConfig> configs =
                    Lists.newArrayList(configService.getPluginConfigs());
            boolean found = false;
            for (ListIterator<PluginConfig> i = configs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginConfig.id().equals(loopPluginConfig.id())) {
                    checkVersionsEqual(loopPluginConfig.version(), priorVersion);
                    i.set(pluginConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Plugin config not found: %s", pluginConfig.id());
            configService.updatePluginConfigs(configs);
        }
        configService.notifyPluginConfigListeners(pluginConfig.id());
        return pluginConfig.version();
    }

    @Override
    public String insertInstrumentationConfig(String server,
            InstrumentationConfig instrumentationConfig) throws IOException {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            configs.add(instrumentationConfig);
            configService.updateInstrumentationConfigs(configs);
        }
        configService.notifyConfigListeners();
        return instrumentationConfig.version();
    }

    @Override
    public String updateInstrumentationConfig(String server,
            InstrumentationConfig instrumentationConfig, String priorVersion) throws IOException {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(instrumentationConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Instrumentation config not found: %s", priorVersion);
            configService.updateInstrumentationConfigs(configs);
        }
        configService.notifyConfigListeners();
        return instrumentationConfig.version();
    }

    @Override
    public void deleteInstrumentationConfig(String server, String version) throws IOException {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Instrumentation config not found: %s", version);
            configService.updateInstrumentationConfigs(configs);
        }
        configService.notifyConfigListeners();
    }

    @Override
    public String insertGaugeConfig(String server, GaugeConfig gaugeConfig) throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            // check for duplicate mbeanObjectName
            for (GaugeConfig loopConfig : configs) {
                if (loopConfig.mbeanObjectName().equals(gaugeConfig.mbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            configs.add(gaugeConfig);
            configService.updateGaugeConfigs(configs);
        }
        configService.notifyConfigListeners();
        return gaugeConfig.version();
    }

    @Override
    public String updateGaugeConfig(String server, GaugeConfig gaugeConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = configs.listIterator(); i.hasNext();) {
                GaugeConfig loopConfig = i.next();
                if (priorVersion.equals(loopConfig.version())) {
                    i.set(gaugeConfig);
                    found = true;
                    break;
                } else if (loopConfig.mbeanObjectName().equals(gaugeConfig.mbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            checkState(found, "Gauge config not found: %s", priorVersion);
            configService.updateGaugeConfigs(configs);
        }
        configService.notifyConfigListeners();
        return gaugeConfig.version();
    }

    @Override
    public void deleteGaugeConfig(String server, String version) throws IOException {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = configs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Gauge config not found: %s", version);
            configService.updateGaugeConfigs(configs);
        }
        configService.notifyConfigListeners();
    }

    @Override
    public String insertAlertConfig(String server, AlertConfig alertConfig) throws Exception {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(alertConfigs);
            configs.add(alertConfig);
            configService.updateOtherConfig("alerts", configs);
            this.alertConfigs = ImmutableList.copyOf(configs);
        }
        configService.notifyConfigListeners();
        return alertConfig.version();
    }

    @Override
    public String updateAlertConfig(String server, AlertConfig alertConfig, String priorVersion)
            throws IOException {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(alertConfigs);
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(alertConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", priorVersion);
            configService.updateOtherConfig("alerts", configs);
            alertConfigs = ImmutableList.copyOf(configs);
        }
        notifyConfigListeners();
        return alertConfig.version();
    }

    @Override
    public void deleteAlertConfig(String server, String version) throws IOException {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(alertConfigs);
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", version);
            configService.updateOtherConfig("alerts", configs);
            alertConfigs = ImmutableList.copyOf(configs);
        }
        notifyConfigListeners();
    }

    @Override
    public String updateUserInterfaceConfig(UserInterfaceConfig updatedConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(userInterfaceConfig.version(), priorVersion);
            configService.updateOtherConfig("ui", updatedConfig);
            userInterfaceConfig = updatedConfig;
        }
        notifyConfigListeners();
        configService.notifyConfigListeners();
        return updatedConfig.version();
    }

    @Override
    public String updateStorageConfig(StorageConfig updatedConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(storageConfig.version(), priorVersion);
            configService.updateOtherConfig("storage", updatedConfig);
            storageConfig = updatedConfig;
        }
        notifyConfigListeners();
        return updatedConfig.version();
    }

    @Override
    public String updateSmtpConfig(SmtpConfig updatedConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(smtpConfig.version(), priorVersion);
            configService.updateOtherConfig("smtp", updatedConfig);
            smtpConfig = updatedConfig;
        }
        notifyConfigListeners();
        return smtpConfig.version();
    }

    @Override
    public String getDefaultDisplayedTransactionType(String server) {
        String defaultDisplayedTransactionType =
                userInterfaceConfig.defaultDisplayedTransactionType();
        if (!defaultDisplayedTransactionType.isEmpty()) {
            return defaultDisplayedTransactionType;
        }
        return getDefaultDisplayedTransactionType(configService.getInstrumentationConfigs());
    }

    @Override
    public ImmutableList<String> getAllTransactionTypes(String server) {
        Set<String> transactionTypes = Sets.newLinkedHashSet();
        for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
            PluginConfig pluginConfig = getPluginConfig(server, pluginDescriptor.id());
            if (pluginConfig != null && pluginConfig.enabled()) {
                transactionTypes.addAll(pluginDescriptor.transactionTypes());
                addInstrumentationTransactionTypes(pluginDescriptor.instrumentationConfigs(),
                        transactionTypes, pluginConfig);
            }
        }
        addInstrumentationTransactionTypes(getInstrumentationConfigs(server), transactionTypes,
                null);
        return ImmutableList.copyOf(transactionTypes);
    }

    @Override
    public long getGaugeCollectionIntervalMillis() {
        return configService.getGaugeCollectionIntervalMillis();
    }

    @Override
    public ImmutableList<RollupConfig> getRollupConfigs() {
        return rollupConfigs;
    }

    // lazy create secret file only when needed
    @Override
    public SecretKey getSecretKey() throws Exception {
        synchronized (secretFile) {
            if (secretKey == null) {
                if (secretFile.exists()) {
                    secretKey = Encryption.loadKey(secretFile);
                } else {
                    secretKey = Encryption.generateNewKey();
                    Files.write(secretKey.getEncoded(), secretFile);
                }
            }
            return secretKey;
        }
    }

    @Override
    public void addListener(final ConfigListener listener) {
        configListeners.add(listener);
        configService.addConfigListener(new org.glowroot.agent.plugin.api.config.ConfigListener() {
            @Override
            public void onChange() {
                listener.onChange();
            }
        });
    }

    @Override
    @OnlyUsedByTests
    public void resetAllConfig(String server) throws IOException {
        userInterfaceConfig = ImmutableUserInterfaceConfig.builder().build();
        storageConfig = ImmutableStorageConfig.builder().build();
        smtpConfig = ImmutableSmtpConfig.builder().build();
        alertConfigs = ImmutableList.of();
        configService.resetAllConfig();
        writeAll();
    }

    private void checkVersionsEqual(String version, String priorVersion)
            throws OptimisticLockException {
        if (!version.equals(priorVersion)) {
            throw new OptimisticLockException();
        }
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    private void notifyConfigListeners() {
        for (ConfigListener configListener : configListeners) {
            configListener.onChange();
        }
    }

    private void writeAll() throws IOException {
        // linked hash map to preserve ordering when writing to config file
        Map<String, Object> configs = Maps.newLinkedHashMap();
        configs.put("ui", userInterfaceConfig);
        configs.put("storage", storageConfig);
        configs.put("smtp", smtpConfig);
        configs.put("alerts", alertConfigs);
        configService.updateOtherConfigs(configs);
    }

    private String getDefaultDisplayedTransactionType(List<InstrumentationConfig> configs) {
        for (PluginDescriptor descriptor : pluginDescriptors) {
            if (!descriptor.transactionTypes().isEmpty()) {
                return descriptor.transactionTypes().get(0);
            }
        }
        for (InstrumentationConfig config : configs) {
            if (!config.transactionType().isEmpty()) {
                return config.transactionType();
            }
        }
        return "";
    }

    private static StorageConfig withCorrectedLists(StorageConfig storageConfig) {
        StorageConfig defaultConfig = ImmutableStorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(storageConfig.rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        ImmutableList<Integer> rollupCappedDatabaseSizesMb =
                fix(storageConfig.rollupCappedDatabaseSizesMb(),
                        defaultConfig.rollupCappedDatabaseSizesMb());
        return ImmutableStorageConfig.builder()
                .copyFrom(storageConfig)
                .rollupExpirationHours(rollupExpirationHours)
                .rollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb)
                .build();
    }

    private static ImmutableList<Integer> fix(ImmutableList<Integer> thisList,
            List<Integer> defaultList) {
        if (thisList.size() >= defaultList.size()) {
            return thisList.subList(0, defaultList.size());
        }
        List<Integer> correctedList = Lists.newArrayList(thisList);
        for (int i = thisList.size(); i < defaultList.size(); i++) {
            correctedList.add(defaultList.get(i));
        }
        return ImmutableList.copyOf(correctedList);
    }

    private static void addInstrumentationTransactionTypes(List<InstrumentationConfig> configs,
            Set<String> transactionTypes, @Nullable PluginConfig pluginConfig) {
        for (InstrumentationConfig config : configs) {
            String transactionType = config.transactionType();
            if (transactionType.isEmpty()) {
                continue;
            }
            if (pluginConfig == null || isEnabled(config, pluginConfig)) {
                transactionTypes.add(transactionType);
            }
        }
    }

    private static boolean isEnabled(InstrumentationConfig config, PluginConfig pluginConfig) {
        return isEnabled(config.enabledProperty(), pluginConfig)
                && isEnabled(config.traceEntryEnabledProperty(), pluginConfig);
    }

    private static boolean isEnabled(String propertyName, PluginConfig pluginConfig) {
        return propertyName.isEmpty() || pluginConfig.getBooleanProperty(propertyName);
    }
}
