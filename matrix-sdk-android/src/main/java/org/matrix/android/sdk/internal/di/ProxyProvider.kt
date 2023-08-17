/*
 * Copyright (c) 2023 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.di

import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.api.util.ConnectionType
import org.matrix.android.sdk.api.util.ProxyType
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject

internal class ProxyProvider @Inject constructor(
        private val lightweightSettingsStorage: LightweightSettingsStorage
) {
    private val proxy: Proxy
        get() {
            return when (lightweightSettingsStorage.getConnectionType()) {
                ConnectionType.MATRIX -> {
                    when (lightweightSettingsStorage.getProxyType()) {
                        ProxyType.NO_PROXY -> Proxy.NO_PROXY
                        else -> {
                            val port = lightweightSettingsStorage.getProxyPort()
                            val host = lightweightSettingsStorage.getProxyHost()
                            val proxyType = ProxyType.toProxy(lightweightSettingsStorage.getProxyType())
                            when (port != 0 && host.isNotEmpty() && proxyType != null) {
                                true -> Proxy(proxyType, InetSocketAddress(host, port))
                                false -> Proxy.NO_PROXY
                            }
                        }
                    }
                }
                ConnectionType.ONION -> {
                    when(val port = lightweightSettingsStorage.getProxyPort()) {
                        0 -> Proxy.NO_PROXY
                        else -> Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
                    }
                }
                ConnectionType.I2P -> Proxy.NO_PROXY
            }
        }

    fun providesProxy(): Proxy {
        return proxy
    }
}
