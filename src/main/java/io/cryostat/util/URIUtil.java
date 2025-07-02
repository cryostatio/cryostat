/*
 * Copyright The Cryostat Authors.
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
package io.cryostat.util;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import javax.management.remote.JMXServiceURL;

import org.openjdk.jmc.rjmx.common.ConnectionToolkit;

import io.cryostat.ConfigProperties;
import io.cryostat.core.net.JFRConnectionToolkit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriBuilder;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class URIUtil {

    @Inject JFRConnectionToolkit toolkit;

    private static final InetAddressValidator validator = InetAddressValidator.getInstance();

    @ConfigProperty(name = ConfigProperties.URI_RANGE)
    String uriRange;

    public URI getRmiTarget(JMXServiceURL serviceUrl) throws URISyntaxException {
        return UriBuilder.newInstance()
                .host(ipv6Compat(toolkit.getHostName(serviceUrl)))
                .port(toolkit.getPort(serviceUrl))
                .build();
    }

    public boolean validateUri(URI uri) throws MalformedURLException {
        if (isJmxUrl(uri)) {
            return validateJmxServiceURL(new JMXServiceURL(uri.toString()));
        }
        return URIRange.fromString(uriRange).validate(uri.getHost());
    }

    public boolean isJmxUrl(URI uri) {
        try {
            new JMXServiceURL(uri.toString());
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    boolean validateJmxServiceURL(JMXServiceURL jmxUrl) {
        return URIRange.fromString(uriRange)
                .validate(ipv6Compat(ConnectionToolkit.getHostName(jmxUrl)));
    }

    private static String ipv6Compat(String s) {
        if (validator.isValidInet6Address(s) && !s.startsWith("[")) {
            s = String.format("[%s]", s);
        }
        return s;
    }
}
