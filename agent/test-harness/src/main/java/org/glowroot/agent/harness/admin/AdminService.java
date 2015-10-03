/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.harness.admin;

import org.glowroot.agent.harness.common.HttpClient;

public class AdminService {

    private final HttpClient httpClient;

    public AdminService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public int getNumPendingCompleteTransactions() throws Exception {
        return Integer.parseInt(
                httpClient.get("/backend/admin/num-pending-complete-transactions?server="));
    }

    public long getNumTraces() throws Exception {
        return Long.parseLong(httpClient.get("/backend/admin/num-traces?server="));
    }

    public void deleteAllData() throws Exception {
        httpClient.post("/backend/admin/delete-all-data", "{\"serverGroup\":\"\"}");
    }
}
