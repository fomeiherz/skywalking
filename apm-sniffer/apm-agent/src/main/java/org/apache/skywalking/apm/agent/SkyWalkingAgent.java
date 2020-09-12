/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import org.apache.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.conf.ConfigNotFoundException;
import org.apache.skywalking.apm.agent.core.conf.SnifferConfigInitializer;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.AbstractClassEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.EnhanceContext;
import org.apache.skywalking.apm.agent.core.plugin.InstrumentDebuggingClass;
import org.apache.skywalking.apm.agent.core.plugin.PluginBootstrap;
import org.apache.skywalking.apm.agent.core.plugin.PluginException;
import org.apache.skywalking.apm.agent.core.plugin.PluginFinder;
import org.apache.skywalking.apm.agent.core.plugin.bootstrap.BootstrapInstrumentBoost;
import org.apache.skywalking.apm.agent.core.plugin.jdk9module.JDK9ModuleExporter;

import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * The main entrance of sky-walking agent, based on javaagent mechanism.
 *
 * @author wusheng
 */
public class SkyWalkingAgent {
    private static final ILog logger = LogManager.getLogger(SkyWalkingAgent.class);

    /**
     * Main entrance. Use byte-buddy transform to enhance all classes, which define in plugins.
     *
     * @param agentArgs       -javaagent 命令携带的参数。如：skywalking.agent.service_name 或 skywalking.collector.backend_service
     * @param instrumentation java.lang.instrumen.Instrumentation 接口：提供了操作类定义的相关方法
     * Instrumentation 接口：
     *     addTransformer()/removeTransformer() 方法：注册/注销一个 ClassFileTransformer 类的实例，该 Transformer 会在类加载的时候被调用，可用于修改类定义。
     *     redefineClasses() 方法：该方法针对的是已经加载的类，它会对传入的类进行重新定义。
     *     **getAllLoadedClasses()方法：**返回当前 JVM 已加载的所有类。
     *     getInitiatedClasses() 方法：返回当前 JVM 已经初始化的类。
     *     getObjectSize()方法：获取参数指定的对象的大小。
     * @throws PluginException
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) throws PluginException, IOException {
        final PluginFinder pluginFinder;
        try {
            SnifferConfigInitializer.initialize(agentArgs);

            pluginFinder = new PluginFinder(new PluginBootstrap().loadPlugins());

        } catch (ConfigNotFoundException ce) {
            logger.error(ce, "SkyWalking agent could not find config. Shutting down.");
            return;
        } catch (AgentPackageNotFoundException ape) {
            logger.error(ape, "Locate agent.jar failure. Shutting down.");
            return;
        } catch (Exception e) {
            logger.error(e, "SkyWalking agent initialized failure. Shutting down.");
            return;
        }

        // If true, skywalking agent will save all instrumented classes files in `/debugging` folder.
        final ByteBuddy byteBuddy = new ByteBuddy()
            .with(TypeValidation.of(Config.Agent.IS_OPEN_DEBUGGING_CLASS));

        // 
        // Byte Buddy 会根据 Transformer 指定的规则进行拦截并增强代码
        // Byte Buddy 专门有个 AgentBuilder 来处理 Java Agent 的场景
        AgentBuilder agentBuilder = new AgentBuilder.Default(byteBuddy)
            .ignore( // 忽略某些包名
                nameStartsWith("net.bytebuddy.")
                    .or(nameStartsWith("org.slf4j."))
                    .or(nameStartsWith("org.groovy."))
                    .or(nameContains("javassist"))
                    .or(nameContains(".asm."))
                    .or(nameContains(".reflectasm."))
                    .or(nameStartsWith("sun.reflect"))
                    .or(allSkyWalkingAgentExcludeToolkit())
                    .or(ElementMatchers.<TypeDescription>isSynthetic()));

        JDK9ModuleExporter.EdgeClasses edgeClasses = new JDK9ModuleExporter.EdgeClasses();
        try {
            agentBuilder = BootstrapInstrumentBoost.inject(pluginFinder, instrumentation, agentBuilder, edgeClasses);
        } catch (Exception e) {
            logger.error(e, "SkyWalking agent inject bootstrap instrumentation failure. Shutting down.");
            return;
        }

        try {
            agentBuilder = JDK9ModuleExporter.openReadEdge(instrumentation, agentBuilder, edgeClasses);
        } catch (Exception e) {
            logger.error(e, "SkyWalking agent open read edge in JDK 9+ failure. Shutting down.");
            return;
        }

        // 建造者模式（builder模式）
        agentBuilder
            .type(pluginFinder.buildMatch()) // 根据包名前缀拦截类
            .transform(new Transformer(pluginFinder)) // 拦截到的类由 transformer 处理
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new Listener())
            .installOn(instrumentation);

        try {
            ServiceManager.INSTANCE.boot();
        } catch (Exception e) {
            logger.error(e, "Skywalking agent boot failure.");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                ServiceManager.INSTANCE.shutdown();
            }
        }, "skywalking service shutdown thread"));
    }

    private static class Transformer implements AgentBuilder.Transformer {
        private PluginFinder pluginFinder;

        Transformer(PluginFinder pluginFinder) {
            this.pluginFinder = pluginFinder;
        }

        @Override
        public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription,
            ClassLoader classLoader, JavaModule module) {
            List<AbstractClassEnhancePluginDefine> pluginDefines = pluginFinder.find(typeDescription);
            if (pluginDefines.size() > 0) {
                DynamicType.Builder<?> newBuilder = builder;
                EnhanceContext context = new EnhanceContext();
                for (AbstractClassEnhancePluginDefine define : pluginDefines) {
                    DynamicType.Builder<?> possibleNewBuilder = define.define(typeDescription, newBuilder, classLoader, context);
                    if (possibleNewBuilder != null) {
                        newBuilder = possibleNewBuilder;
                    }
                }
                if (context.isEnhanced()) {
                    logger.debug("Finish the prepare stage for {}.", typeDescription.getName());
                }

                return newBuilder;
            }

            logger.debug("Matched class {}, but ignore by finding mechanism.", typeDescription.getTypeName());
            return builder;
        }
    }

    private static ElementMatcher.Junction<NamedElement> allSkyWalkingAgentExcludeToolkit() {
        return nameStartsWith("org.apache.skywalking.").and(not(nameStartsWith("org.apache.skywalking.apm.toolkit.")));
    }

    private static class Listener implements AgentBuilder.Listener {
        @Override
        public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {

        }

        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
            boolean loaded, DynamicType dynamicType) {
            if (logger.isDebugEnable()) {
                logger.debug("On Transformation class {}.", typeDescription.getName());
            }

            InstrumentDebuggingClass.INSTANCE.log(dynamicType);
        }

        @Override
        public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
            boolean loaded) {

        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded,
            Throwable throwable) {
            logger.error("Enhance class " + typeName + " error.", throwable);
        }

        @Override
        public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
        }
    }
}
