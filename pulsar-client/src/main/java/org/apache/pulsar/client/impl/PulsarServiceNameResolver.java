/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.impl;

import static com.google.common.base.Preconditions.checkState;

import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException.InvalidServiceURL;
import org.apache.pulsar.common.net.ServiceURI;

/**
 * The default implementation of {@link ServiceNameResolver}.
 */
@Slf4j
public class PulsarServiceNameResolver implements ServiceNameResolver {

    private volatile ServiceURI serviceUri;
    private volatile String serviceUrl;
    private volatile int currentIndex;
    private volatile List<InetSocketAddress> addressList;
    private volatile List<InetSocketAddress> unReachableAddressList
            = new ArrayList<InetSocketAddress>();

    private boolean checkReachable = true;

    @Override
    public InetSocketAddress resolveHost() {
        List<InetSocketAddress> list = addressList;
        checkState(
            list != null, "No service url is provided yet");
        checkState(
            !list.isEmpty(), "No hosts found for service url : " + serviceUrl);

        InetSocketAddress address = null;
        if(unReachableAddressList.size() == list.size()){
            unReachableAddressList.clear();
        }
        if (list.size() == 1) {
            if(isAddressReachable(list.get(0))){
                address = list.get(0);
            }
        } else {
            for (int i = 0; i < list.size(); i++){
                currentIndex = (currentIndex + 1) % list.size();
                if(!unReachableAddressList.contains(list.get(currentIndex))
                        && isAddressReachable(list.get(currentIndex))){
                    address = list.get(currentIndex);
                    break;
                }
            }
        }
        checkState(
                address != null, "No host is reachable for service url :"  + serviceUrl);
        return address;
    }

    @Override
    public URI resolveHostUri() {
        InetSocketAddress host = resolveHost();
        String hostUrl = serviceUri.getServiceScheme() + "://" + host.getHostName() + ":" + host.getPort();
        return URI.create(hostUrl);
    }

    @Override
    public String getServiceUrl() {
        return serviceUrl;
    }

    @Override
    public ServiceURI getServiceUri() {
        return serviceUri;
    }

    @Override
    public void updateServiceUrl(String serviceUrl) throws InvalidServiceURL {
        ServiceURI uri;
        try {
            uri = ServiceURI.create(serviceUrl);
        } catch (IllegalArgumentException iae) {
            log.error("Invalid service-url {} provided {}", serviceUrl, iae.getMessage(), iae);
            throw new InvalidServiceURL(iae);
        }

        String[] hosts = uri.getServiceHosts();
        List<InetSocketAddress> addresses = new ArrayList<>(hosts.length);
        for (String host : hosts) {
            String hostUrl = uri.getServiceScheme() + "://" + host;
            try {
                URI hostUri = new URI(hostUrl);
                addresses.add(InetSocketAddress.createUnresolved(hostUri.getHost(), hostUri.getPort()));
            } catch (URISyntaxException e) {
                log.error("Invalid host provided {}", hostUrl, e);
                throw new InvalidServiceURL(e);
            }
        }
        this.addressList = addresses;
        this.serviceUrl = serviceUrl;
        this.serviceUri = uri;
        this.currentIndex = randomIndex(addresses.size());
    }

    public void disableCheckReachable(){
        this.checkReachable = false;
    }

    public void enableCheckReachable(){
        this.checkReachable = true;
    }

    private boolean isAddressReachable(InetSocketAddress address){
        if(!checkReachable){
            return true;
        }
        try {
            Socket socket = new Socket(address.getHostName(), address.getPort());
            socket.close();
            unReachableAddressList.remove(address);
            return true;
        } catch (IOException e) {
            log.warn("Address unreachable {}",address.toString());
        }
        unReachableAddressList.add(address);
        return false;
    }

    private static int randomIndex(int numAddresses) {
        return numAddresses == 1 ? 0 : PlatformDependent.threadLocalRandom().nextInt(numAddresses);
    }
}
