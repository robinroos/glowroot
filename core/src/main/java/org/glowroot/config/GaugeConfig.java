/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.config;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.common.Marshaling2;

import static com.google.common.base.Preconditions.checkNotNull;

@Value.Immutable
@Json.Marshaled
public abstract class GaugeConfig {

    public static final Ordering<GaugeConfig> orderingByName = new Ordering<GaugeConfig>() {
        @Override
        public int compare(@Nullable GaugeConfig left, @Nullable GaugeConfig right) {
            checkNotNull(left);
            checkNotNull(right);
            return left.display().compareToIgnoreCase(right.display());
        }
    };

    public abstract String mbeanObjectName();
    @Json.ForceEmpty
    public abstract List<MBeanAttribute> mbeanAttributes();

    @Value.Derived
    @Json.Ignore
    public String display() {
        // e.g. java.lang:name=PS Eden Space,type=MemoryPool
        List<String> parts = Splitter.on(CharMatcher.anyOf(":,")).splitToList(mbeanObjectName());
        StringBuilder name = new StringBuilder();
        name.append(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            name.append('/');
            name.append(parts.get(i).split("=")[1]);
        }
        return name.toString();
    }

    @Value.Derived
    @Json.Ignore
    public String version() {
        return Hashing.sha1().hashString(Marshaling2.toJson(this), Charsets.UTF_8).toString();
    }

    @Value.Immutable
    @Json.Marshaled
    public abstract static class MBeanAttribute {

        @Value.Parameter
        public abstract String name();
        @Value.Parameter
        public abstract boolean everIncreasing();
    }
}
