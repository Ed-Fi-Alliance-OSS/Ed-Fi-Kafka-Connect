// SPDX-License-Identifier: Apache-2.0
// Licensed to the Ed-Fi Alliance under one or more agreements.
// The Ed-Fi Alliance licenses this file to you under the Apache License, Version 2.0.
// See the LICENSE and NOTICES files in the project root for more information.

/** Keeps the image JVM alive while packaging smoke probes the standard agent endpoint. */
public final class CdcExporterSmoke {
    public static void main(String[] args) throws InterruptedException {
        if (args.length > 0 && !args[0].equals(System.getProperty("edfi.smoke.option"))) {
            throw new IllegalStateException("Runtime KAFKA_OPTS was not preserved.");
        }
        // The harness owns the deadline and removes the container on success or failure.
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
