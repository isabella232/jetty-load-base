//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.load.probe;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.beust.jcommander.DynamicParameter;
import com.beust.jcommander.Parameter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.load.MonitoredQueuedThreadPool;
import org.eclipse.jetty.load.Version;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.mortbay.jetty.load.generator.LoadGenerator;
import org.mortbay.jetty.load.generator.Resource;
import org.mortbay.jetty.load.generator.listeners.LoadConfig;
import org.mortbay.jetty.load.generator.listeners.LoadResult;
import org.mortbay.jetty.load.generator.listeners.ServerInfo;
import org.mortbay.jetty.load.generator.starter.LoadGeneratorStarter;
import org.mortbay.jetty.load.generator.starter.LoadGeneratorStarterArgs;
import org.mortbay.jetty.load.generator.store.ResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProbeMain
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ProbeMain.class);

    public static void main(String[] args) throws Exception
    {
        ProbeArgs probeArgs = LoadGeneratorStarter.parse(args, ProbeArgs::new);
        LoadGenerator.Builder builder = LoadGeneratorStarter.configure(probeArgs);

        QueuedThreadPool executor = null;
        if (probeArgs.sharedThreads > 0)
        {
            executor = new MonitoredQueuedThreadPool(probeArgs.sharedThreads);
            executor.setName("probe");
            executor.start();
            builder.executor(executor);
        }

        Scheduler scheduler = new ScheduledExecutorScheduler("probe-scheduler", false);
        scheduler.start();
        builder.scheduler(scheduler);

        HttpClient httpClient = new HttpClient(probeArgs.getHttpClientTransportBuilder().build(), null);
        httpClient.setScheduler(scheduler);
        httpClient.start();

        try
        {
            ServerInfo serverInfo = ServerInfo.retrieveServerInfo(new ServerInfo.Request(probeArgs.getScheme(),
                    probeArgs.getHost(),
                    "/test/info/",
                    probeArgs.getPort()), httpClient);
            if(serverInfo.getJettyVersion()==null)
            {
                serverInfo.setJettyVersion(probeArgs.jettyVersion);
            }
            LOGGER.info("run load test on server:{}", serverInfo);
            LOGGER.info("Probe version: {}", Version.getInstance());

            LiveProbeDisplayListener listener = new LiveProbeDisplayListener().serverInfo(serverInfo)
                    .loadConfigType(LoadConfig.Type.PROBE);
            builder = builder.listener(listener).resourceListener(listener).requestListener(listener);


            // Print probe activity periodically.
            schedule(scheduler, new Runnable()
            {
                @Override
                public void run()
                {
                    listener.run();
                    schedule(scheduler, this);
                }
            });

            ContentResponse contentResponse =
                    httpClient.newRequest(probeArgs.getHost(), probeArgs.getPort())
                        .scheme(probeArgs.getScheme())
                        .path("/stats/start")
                        .send();
            if (contentResponse.getStatus() != 200)
            {
                LOGGER.info("Fail to start stats " + contentResponse.getStatus()
                        +", content:"+contentResponse.getContentAsString());
            }

            LoadConfig loadConfig = retrieveLoaderConfig(probeArgs, httpClient);
            if (!probeArgs.skipRetrieveLoaderConfig)
            {
                long startRetrieve = System.currentTimeMillis();
                // max wait 60 s
                long maxWaitSecond = 120;
                while (loadConfig == null)
                {
                    loadConfig = retrieveLoaderConfig(probeArgs, httpClient);
                    if (loadConfig != null)
                    {
                        break;
                    }
                    Thread.sleep(1000);
                    if (TimeUnit.SECONDS.convert(System.currentTimeMillis() - startRetrieve, TimeUnit.MILLISECONDS) >
                            maxWaitSecond)
                    {
                        LOGGER.info("stop probe as loader as not been not started, we cannot retrieve loader config {}",
                                maxWaitSecond);
                        System.exit(1);
                        return;
                    }
                }

                loadConfig.instanceNumber(probeArgs.loaderNumber).transport(probeArgs.getTransport());
            }
            if (probeArgs.skipRetrieveLoaderConfig && loadConfig == null)
            {
                loadConfig = new LoadConfig();
            }
            // we calculate the resource number
            Resource resource = builder.getResource();
            loadConfig.setResourceNumber(resource.descendantCount());

            LOGGER.info("Loader config: {}", loadConfig);

            LOGGER.info("start probe load generator run: {}", builder.toString());
            long start = System.nanoTime();
            LoadGeneratorStarter.run(builder.build());
            long end = System.nanoTime();
            LOGGER.info("end probe load generator run {} seconds", TimeUnit.NANOSECONDS.toSeconds(end - start));

            contentResponse = httpClient.newRequest(probeArgs.getHost(), probeArgs.getPort())
                    .scheme(probeArgs.getScheme())
                    .path("/stats/stop")
                    .send();

            if (contentResponse.getStatus() != 200)
            {
                LOGGER.info("Fail to stop stats " + contentResponse.getStatus()
                        +", content:"+contentResponse.getContentAsString());
            }

            LOGGER.info("stop stats");

            LoadResult loadResult = listener.getLoadResult().transport(probeArgs.getTransport());
            loadResult.addLoadConfig(loadConfig);

            String jenkinsBuildId = probeArgs.dynamicParams.get("jenkins.buildId");
            LOGGER.info("jenkinsBuildId {}, dynamicParams {}", jenkinsBuildId, probeArgs.dynamicParams);
            loadResult.uuid(UUID.randomUUID().toString());
            if (StringUtils.isNotEmpty(jenkinsBuildId))
            {
                loadResult.externalId(jenkinsBuildId);
            }
            String comment = probeArgs.dynamicParams.get("loadresult.comment");
            if (StringUtils.isNotEmpty(comment))
            {
                loadResult.setComment(comment);
            }

            StringWriter stringWriter = new StringWriter();
            new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .writeValue(stringWriter, loadResult);
            String jsonLoadResult = stringWriter.toString();
            LOGGER.info("loadResult json: {}", jsonLoadResult);

            String resultFilePath = probeArgs.resultFilePath;
            if (resultFilePath != null)
            {
                Path path = Paths.get(resultFilePath);
                Files.deleteIfExists(path);
                Files.write(path, jsonLoadResult.getBytes(StandardCharsets.UTF_8));
            }

            List<ResultStore> resultStores = ResultStore.getActives(probeArgs.dynamicParams);
            resultStores.forEach(resultStore ->
            {
                try
                {
                    resultStore.initialize(probeArgs.dynamicParams);
                    resultStore.save(loadResult);
                    resultStore.close();
                }
                catch (Throwable e)
                {
                    LOGGER.info("ignore saving result error:" + e.getMessage(), e);
                }
            });
        }
        finally
        {
            LOGGER.info("Finally block in Probe");
            if (executor instanceof MonitoredQueuedThreadPool)
            {
                printThreadPoolStats((MonitoredQueuedThreadPool) executor);
            }
            httpClient.stop();
            scheduler.stop();
            if (executor != null)
            {
                executor.stop();
            }
        }
        LOGGER.info("Probe main end");
        System.exit(0);
        //return;
    }

    private static LoadConfig retrieveLoaderConfig(ProbeArgs probeArgs, HttpClient httpClient) throws Exception
    {
        try
        {
            ContentResponse contentResponse = httpClient.newRequest(probeArgs.getHost(), probeArgs.getPort()) //
                    .timeout(10, TimeUnit.SECONDS) //
                    .method(HttpMethod.GET) //
                    .path("/test/loadConfig") //
                    .send();
            String content = contentResponse.getContentAsString();
            if (contentResponse.getStatus() == HttpStatus.NO_CONTENT_204 || StringUtils.isEmpty(content))
            {
                return null;
            }
            if (contentResponse.getStatus() != HttpStatus.OK_200)
            {
                LOGGER.info("get load config return " + contentResponse.getStatus() +
                        ", content:" +contentResponse.getContentAsString());
                return null;
            }
            return new ObjectMapper() //
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue(content, LoadConfig.class);
        }
        catch (Exception e)
        {
            LOGGER.info("fail to retrieve loaderConfig", e);
            return null;
        }
    }

    private static void schedule(Scheduler scheduler, Runnable task)
    {
        scheduler.schedule(task, 2, TimeUnit.SECONDS);
    }

    private static void printThreadPoolStats(MonitoredQueuedThreadPool threadPool)
    {
        LOGGER.info("thread pool - tasks = {} | concurrent threads max = {} | queue size max = {} | queue latency avg/max = {}/{} ms | task time avg/max = {}/{} ms",
                threadPool.getTasks(),
                threadPool.getMaxActiveThreads(),
                threadPool.getMaxQueueSize(),
                TimeUnit.NANOSECONDS.toMillis(threadPool.getAverageQueueLatency()),
                TimeUnit.NANOSECONDS.toMillis(threadPool.getMaxQueueLatency()),
                TimeUnit.NANOSECONDS.toMillis(threadPool.getAverageTaskLatency()),
                TimeUnit.NANOSECONDS.toMillis(threadPool.getMaxTaskLatency()));
    }

    private static class ProbeArgs extends LoadGeneratorStarterArgs
    {
        @Parameter(names = {"--shared-threads", "-st"}, description = "Max threads of the shared thread pool")
        private int sharedThreads;

        @Parameter(names = {"--result-path", "-rp"}, description = "Path to store json result file")
        private String resultFilePath;

        @DynamicParameter(names = "-D", description = "Dynamic parameters go here")
        private Map<String, String> dynamicParams = new HashMap<>();

        @Parameter(names = {"--loader-resources-path", "-lrp"}, description = "Path to loader resources path")
        private String loaderResourcesPath;

        @Parameter(names = {"--loader-rate", "-lr"}, description = "Loader rate")
        private int loaderRate;

        @Parameter(names = {"--loader-number", "-ln"}, description = "Loader number")
        private int loaderNumber;

        @Parameter(names = {"--skip-loader-config", "-slc"}, description = "Skip retrieving loader config")
        private boolean skipRetrieveLoaderConfig;

        @Parameter(names = {"--jetty-version", "-jv"}, description = "Jetty Version")
        private String jettyVersion;

    }
}
