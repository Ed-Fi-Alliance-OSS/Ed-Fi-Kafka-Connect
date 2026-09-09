// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

import java.util.Map;

import org.apache.kafka.connect.runtime.isolation.PluginClassLoader;
import org.apache.kafka.connect.runtime.isolation.Plugins;

/** Checks Connect's actual selection, rather than only discovering the plugin directory. */
public final class PluginIsolationSmoke {
    public static void main(String[] args) throws Exception {
        Plugins plugins = new Plugins(Map.of("plugin.path", "/kafka/connect"));
        for (String name : new String[] {
                "org.edfi.kafka.connect.transforms.ExpandJson$Value",
                "org.edfi.kafka.connect.transforms.DebeziumDeletedToTombstone",
                "org.edfi.kafka.connect.transforms.DocumentState",
                "org.edfi.kafka.connect.converters.DocumentStateJsonConverter"}) {
            try {
                Class.forName(name, false, ClassLoader.getSystemClassLoader());
                throw new IllegalStateException(name + " is present on the worker classpath");
            } catch (ClassNotFoundException expected) {
                // Only the plugin path should supply these classes.
            }
            Object plugin = plugins.newPlugin(name, null);
            ClassLoader loader = plugin.getClass().getClassLoader();
            if (!(loader instanceof PluginClassLoader isolated)
                    || !isolated.location().contains("/kafka/connect/ed-fi-kafka-connect-transforms")) {
                throw new IllegalStateException(name + " loaded outside its isolated directory: " + loader);
            }
            System.out.println("OK: " + name + " selected from " + loader);
        }
    }
}
