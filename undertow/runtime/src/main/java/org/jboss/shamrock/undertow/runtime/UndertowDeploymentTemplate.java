/*
 * Copyright 2018 Red Hat, Inc.
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

package org.jboss.shamrock.undertow.runtime;

import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.EventListener;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.jboss.shamrock.runtime.InjectionFactory;
import org.jboss.shamrock.runtime.InjectionInstance;
import org.jboss.shamrock.runtime.RuntimeValue;
import org.jboss.shamrock.runtime.ShutdownContext;
import org.jboss.shamrock.runtime.Template;
import org.jboss.shamrock.runtime.cdi.BeanContainer;

import io.undertow.Undertow;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.CanonicalPathHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.session.SessionIdGenerator;
import io.undertow.servlet.ServletExtension;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.ClassIntrospecter;
import io.undertow.servlet.api.Deployment;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.InstanceFactory;
import io.undertow.servlet.api.InstanceHandle;
import io.undertow.servlet.api.ListenerInfo;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.api.ServletSecurityInfo;

/**
 * Provides the runtime methods to bootstrap Undertow. This class is present in the final uber-jar,
 * and is invoked from generated bytecode
 */
@Template
public class UndertowDeploymentTemplate {

    private static final Logger log = Logger.getLogger(UndertowDeploymentTemplate.class.getName());

    public static final HttpHandler ROOT_HANDLER = new HttpHandler() {
        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            currentRoot.handleRequest(exchange);
        }
    };
    private static final String RESOURCES_PROP = "shamrock.undertow.resources";

    private static volatile Undertow undertow;
    private static volatile HttpHandler currentRoot;

    public RuntimeValue<DeploymentInfo> createDeployment(String name, Set<String> knownFile, Set<String> knownDirectories) {
        DeploymentInfo d = new DeploymentInfo();
        d.setSessionIdGenerator(new ShamrockSessionIdGenerator());
        d.setClassLoader(getClass().getClassLoader());
        d.setDeploymentName(name);
        d.setContextPath("/");
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = new ClassLoader() {
            };
        }
        d.setClassLoader(cl);
        //TODO: this is a big hack
        String resourcesDir = System.getProperty(RESOURCES_PROP);
        if (resourcesDir == null) {
            d.setResourceManager(new KnownPathResourceManager(knownFile, knownDirectories, new ClassPathResourceManager(d.getClassLoader(), "META-INF/resources")));
        } else {
            d.setResourceManager(new PathResourceManager(Paths.get(resourcesDir)));
        }
        d.addWelcomePages("index.html", "index.htm");
        return new RuntimeValue<>(d);
    }

    public <T> InstanceFactory<T> createInstanceFactory(InjectionInstance<T> injectionInstance) {
        return new ShamrockInstanceFactory<T>(injectionInstance);
    }

    public RuntimeValue<ServletInfo> registerServlet(RuntimeValue<DeploymentInfo> deploymentInfo,
                                                     String name,
                                                     Class<?> servletClass,
                                                     boolean asyncSupported,
                                                     int loadOnStartup,
                                                     InjectionFactory instanceFactory) throws Exception {
        ServletInfo servletInfo = new ServletInfo(name, (Class<? extends Servlet>) servletClass, new ShamrockInstanceFactory(instanceFactory.create(servletClass)));
        deploymentInfo.getValue().addServlet(servletInfo);
        servletInfo.setAsyncSupported(asyncSupported);
        if (loadOnStartup > 0) {
            servletInfo.setLoadOnStartup(loadOnStartup);
        }
        return new RuntimeValue<>(servletInfo);
    }

    public void addServletInitParam(RuntimeValue<ServletInfo> info, String name, String value) {
        info.getValue().addInitParam(name, value);
    }

    public void addServletMapping(RuntimeValue<DeploymentInfo> info, String name, String mapping) throws Exception {
        ServletInfo sv = info.getValue().getServlets().get(name);
        sv.addMapping(mapping);
    }

    public void setMultipartConfig(RuntimeValue<ServletInfo> sref, String location, long fileSize, long maxRequestSize, int fileSizeThreshold) {
        MultipartConfigElement mp = new MultipartConfigElement(location, fileSize, maxRequestSize, fileSizeThreshold);
        sref.getValue().setMultipartConfig(mp);
    }

    /**
     * @param sref
     * @param securityInfo
     */
    public void setSecurityInfo(RuntimeValue<ServletInfo> sref, ServletSecurityInfo securityInfo) {
        sref.getValue().setServletSecurityInfo(securityInfo);
    }

    /**
     * @param sref
     * @param roleName
     * @param roleLink
     */
    public void addSecurityRoleRef(RuntimeValue<ServletInfo> sref, String roleName, String roleLink) {
        sref.getValue().addSecurityRoleRef(roleName, roleLink);
    }

    public RuntimeValue<FilterInfo> registerFilter(RuntimeValue<DeploymentInfo> info,
                                                   String name, Class<?> filterClass,
                                                   boolean asyncSupported,
                                                   InjectionFactory instanceFactory) throws Exception {
        FilterInfo filterInfo = new FilterInfo(name, (Class<? extends Filter>) filterClass, new ShamrockInstanceFactory(instanceFactory.create(filterClass)));
        info.getValue().addFilter(filterInfo);
        filterInfo.setAsyncSupported(asyncSupported);
        return new RuntimeValue<>(filterInfo);
    }

    public void addFilterInitParam(RuntimeValue<FilterInfo> info, String name, String value) {
        info.getValue().addInitParam(name, value);
    }

    public void addFilterURLMapping(RuntimeValue<DeploymentInfo> info, String name, String mapping, DispatcherType dispatcherType) throws Exception {
        info.getValue().addFilterUrlMapping(name, mapping, dispatcherType);
    }

    public void addFilterServletNameMapping(RuntimeValue<DeploymentInfo> info, String name, String mapping, DispatcherType dispatcherType) throws Exception {
        info.getValue().addFilterServletNameMapping(name, mapping, dispatcherType);
    }

    public void registerListener(RuntimeValue<DeploymentInfo> info, Class<?> listenerClass, InjectionFactory factory) {
        info.getValue().addListener(new ListenerInfo((Class<? extends EventListener>) listenerClass, (InstanceFactory<? extends EventListener>) new ShamrockInstanceFactory<>(factory.create(listenerClass))));
    }

    public void addServltInitParameter(RuntimeValue<DeploymentInfo> info, String name, String value) {
        info.getValue().addInitParameter(name, value);
    }

    public RuntimeValue<Undertow> startUndertow(ShutdownContext shutdown, Deployment deployment, HttpConfig config, List<HandlerWrapper> wrappers) throws ServletException {
        if (undertow == null) {
            startUndertowEagerly(config, null);

            //in development mode undertow is started eagerly
            shutdown.addShutdownTask(new Runnable() {
                @Override
                public void run() {
                    undertow.stop();
                    undertow = null;
                }
            });
        }
        HttpHandler main = deployment.getHandler();
        for (HandlerWrapper i : wrappers) {
            main = i.wrap(main);
        }
        currentRoot = main;
        return new RuntimeValue<>(undertow);
    }


    /**
     * Used for shamrock:run, where we want undertow to start very early in the process.
     * <p>
     * This enables recovery from errors on boot. In a normal boot undertow is one of the last things start, so there would
     * be no chance to use hot deployment to fix the error. In development mode we start Undertow early, so any error
     * on boot can be corrected via the hot deployment handler
     */
    public static void startUndertowEagerly(HttpConfig config, HandlerWrapper hotDeploymentWrapper) throws ServletException {
        if (undertow == null) {
            log.log(Level.INFO, "Starting Undertow on port " + config.port);
            HttpHandler rootHandler = new CanonicalPathHandler(ROOT_HANDLER);
            if (hotDeploymentWrapper != null) {
                rootHandler = hotDeploymentWrapper.wrap(rootHandler);
            }

            Undertow.Builder builder = Undertow.builder()
                    .addHttpListener(config.port, config.host)
                    .setHandler(rootHandler);
            if (config.ioThreads.isPresent()) {
                builder.setIoThreads(config.ioThreads.get());
            }
            if (config.workerThreads.isPresent()) {
                builder.setWorkerThreads(config.workerThreads.get());
            }
            undertow = builder
                    .build();
            undertow.start();
        }
    }

    public Deployment bootServletContainer(RuntimeValue<DeploymentInfo> info, InjectionFactory injectionFactory) {
        try {
            ClassIntrospecter defaultVal = info.getValue().getClassIntrospecter();
            info.getValue().setClassIntrospecter(new ClassIntrospecter() {
                @Override
                public <T> InstanceFactory<T> createInstanceFactory(Class<T> clazz) throws NoSuchMethodException {
                    InjectionInstance<T> res = injectionFactory.create(clazz);
                    if(res == null) {
                        return defaultVal.createInstanceFactory(clazz);
                    }
                    return new InstanceFactory<T>() {
                        @Override
                        public InstanceHandle<T> createInstance() throws InstantiationException {
                            T ih = res.newInstance();
                            return new InstanceHandle<T>() {
                                @Override
                                public T getInstance() {
                                    return ih;
                                }

                                @Override
                                public void release() {

                                }
                            };
                        }
                    };
                }
            });
            ServletContainer servletContainer = Servlets.defaultContainer();
            DeploymentManager manager = servletContainer.addDeployment(info.getValue());
            manager.deploy();
            manager.start();
            return manager.getDeployment();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void addServletContextAttribute(RuntimeValue<DeploymentInfo> deployment, String key, Object value1) {
        deployment.getValue().addServletContextAttribute(key, value1);
    }

    public void addServletExtension(RuntimeValue<DeploymentInfo> deployment, ServletExtension extension) {
        deployment.getValue().addServletExtension(extension);
    }

    /**
     * we can't have SecureRandom in the native image heap, so we need to lazy init
     */
    private static class ShamrockSessionIdGenerator implements SessionIdGenerator {

        private volatile SecureRandom random;

        private volatile int length = 30;

        private static final char[] SESSION_ID_ALPHABET;

        private static final String ALPHABET_PROPERTY = "io.undertow.server.session.SecureRandomSessionIdGenerator.ALPHABET";

        static {
            String alphabet = System.getProperty(ALPHABET_PROPERTY, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_");
            if (alphabet.length() != 64) {
                throw new RuntimeException("io.undertow.server.session.SecureRandomSessionIdGenerator must be exactly 64 characters long");
            }
            SESSION_ID_ALPHABET = alphabet.toCharArray();
        }

        @Override
        public String createSessionId() {
            if (random == null) {
                random = new SecureRandom();
            }
            final byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            return new String(encode(bytes));
        }


        public int getLength() {
            return length;
        }

        public void setLength(final int length) {
            this.length = length;
        }

        /**
         * Encode the bytes into a String with a slightly modified Base64-algorithm
         * This code was written by Kevin Kelley <kelley@ruralnet.net>
         * and adapted by Thomas Peuss <jboss@peuss.de>
         *
         * @param data The bytes you want to encode
         * @return the encoded String
         */
        private char[] encode(byte[] data) {
            char[] out = new char[((data.length + 2) / 3) * 4];
            char[] alphabet = SESSION_ID_ALPHABET;
            //
            // 3 bytes encode to 4 chars.  Output is always an even
            // multiple of 4 characters.
            //
            for (int i = 0, index = 0; i < data.length; i += 3, index += 4) {
                boolean quad = false;
                boolean trip = false;

                int val = (0xFF & (int) data[i]);
                val <<= 8;
                if ((i + 1) < data.length) {
                    val |= (0xFF & (int) data[i + 1]);
                    trip = true;
                }
                val <<= 8;
                if ((i + 2) < data.length) {
                    val |= (0xFF & (int) data[i + 2]);
                    quad = true;
                }
                out[index + 3] = alphabet[(quad ? (val & 0x3F) : 63)];
                val >>= 6;
                out[index + 2] = alphabet[(trip ? (val & 0x3F) : 63)];
                val >>= 6;
                out[index + 1] = alphabet[val & 0x3F];
                val >>= 6;
                out[index] = alphabet[val & 0x3F];
            }
            return out;
        }
    }
}
