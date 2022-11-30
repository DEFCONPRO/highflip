package com.baidu.highflip.server.engine;

import com.baidu.highflip.core.adaptor.JobAdaptor;
import com.baidu.highflip.core.adaptor.PlatformAdaptor;
import com.baidu.highflip.core.entity.dag.Graph;
import com.baidu.highflip.core.entity.runtime.*;
import com.baidu.highflip.core.entity.runtime.basic.*;
import com.baidu.highflip.core.entity.runtime.version.CompatibleVersion;
import com.baidu.highflip.core.entity.runtime.version.PlatformVersion;
import com.baidu.highflip.server.engine.common.ConfigurationList;
import com.baidu.highflip.server.engine.dataio.PushContext;
import com.baidu.highflip.server.engine.component.HighFlipConfiguration;
import com.baidu.highflip.server.engine.component.HighFlipContext;
import com.baidu.highflip.server.engine.component.HighFlipRuntime;
import com.google.common.collect.Streams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.transaction.Transactional;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class HighFlipEngine {

    @Autowired
    HighFlipContext context;

    @Autowired
    HighFlipConfiguration configuration;

    @Autowired
    HighFlipRuntime runtime;

    @Autowired
    PlatformTransactionManager transactionManager;

    ConcurrentMap<String, Job> activeJobs;

    @Autowired
    AsyncTaskExecutor executor;

    /******************************************************************************
     * COMMON
     ******************************************************************************/

    public HighFlipContext getContext() {
        return context;
    }

    public HighFlipConfiguration getConfiguration() {
        return configuration;
    }

    @PostConstruct
    public void initialize() {
        new TransactionTemplate(transactionManager).execute(
                new TransactionCallbackWithoutResult() {
                    @Override
                    protected void doInTransactionWithoutResult(TransactionStatus status) {
                        initPlatform();

                        initializePartners();
                    }
                });
    }

    @PreDestroy
    public void destroy() {

    }

    /******************************************************************************
     * CONFIG
     ******************************************************************************/


    /******************************************************************************
     * PLATFORM
     ******************************************************************************/
    @Transactional
    public void initPlatform() {
        Boolean isInitialized = getConfiguration().getBoolean(
                ConfigurationList.CONFIG_HIGHFLIP_PLATFORM_IS_INITIALIZED,
                ConfigurationList.CONFIG_HIGHFLIP_PLATFORM_IS_INITIALIZED_DEFAULT);

        if (isInitialized) {
            return;
        }

        log.info("Begin to initialize platform information.");

        getContext().getPlatformRepository()
                .deleteLocal();
        log.info("Delete old platform information.");

        PlatformAdaptor adaptor = getContext().getPlatformAdaptor();
        if (adaptor == null) {
            log.error("Miss platform adaptor in system. Skipped platform initialization.");
            return;
        }

        Platform platform = new Platform();
        platform.setCompany(adaptor.getCompany());
        platform.setProduct(adaptor.getProduct());
        platform.setVersion(adaptor.getVersion());
        platform.setIsLocal(Boolean.TRUE);

        Iterator<CompatibleVersion> iters = adaptor.getCompatibleList();
        if (iters != null) {
            List<CompatibleVersion> compatibles = Streams
                    .stream(iters)
                    .collect(Collectors.toList());
            platform.setCompatibles(compatibles);
        }
        getContext().getPlatformRepository().save(platform);

        getConfiguration().setBoolean(
                ConfigurationList.CONFIG_HIGHFLIP_PLATFORM_IS_INITIALIZED,
                Boolean.TRUE);

        log.info("Finish initialization of platform information.");
    }

    public Platform getPlatform() {
        Platform platform = getContext().getPlatformRepository().findLocal();
        return platform;
    }

    public Platform matchPlatform(PlatformVersion target) {
        for (Platform platform : getContext().getPlatformRepository().findAll()) {
            for (CompatibleVersion comp : platform.getCompatibles()) {
                if (comp.isCompatible(target)) {
                    return platform;
                }
            }
        }
        return null;
    }

    /******************************************************************************
     * JOB
     ******************************************************************************/
    @Transactional
    public void initializeJobs() {
        Boolean isInitialized = getConfiguration().getBoolean(
                ConfigurationList.CONFIG_HIGHFLIP_JOB_IS_INITIALIZED,
                ConfigurationList.CONFIG_HIGHFLIP_JOB_IS_INITIALIZED_DEFAULT);

        if (isInitialized) {
            return;
        }

        log.info("Delete old job information.");
        getContext().getJobRepository()
                .deleteAll();

        int jobCount = getContext()
                .getJobAdaptor()
                .getJobCount();

        for (int i = 0; i < jobCount; i++) {
            Job job = new Job();

            Job newJob = getContext().getJobAdaptor()
                    .getJobByIndex(i, job);

            getContext().getJobRepository()
                    .save(newJob);
        }

        getConfiguration().setBoolean(
                ConfigurationList.CONFIG_HIGHFLIP_JOB_IS_INITIALIZED,
                Boolean.TRUE);
    }

    @Transactional
    public Job synchronizeJob() {
        Job job = new Job();
        Optional<Job> optJob = getContext().getJobAdaptor().moreJob(job, runtime);

        if (optJob == null || optJob.isEmpty()) {
            return null;
        }

        if (runtime.hasJobByBindId(job.getBingingId())) {
            log.error("Detected and skipped duplicated Binding Id {} in jobs synchronization.",
                    job.getBingingId());
        }

        Job savedJob = getContext().getJobRepository()
                .save(job);
        log.info("Found new job, jobId = {}", savedJob.getJobId());
        return savedJob;
    }

    @Scheduled(fixedDelayString = "10s")
    public void synchronizeJobs() {
        try {
            Job job = null;
            do {
                job = synchronizeJob();
            } while (job != null);
        } catch (Exception e) {

        }
    }

    @Transactional
    @CachePut("jobs")
    public Job createJob(String name, String description, Graph graph) {
        Job job = new Job();
        job.setJobName(name);
        job.setDescription(description);
        job.setGraph(graph);

        JobAdaptor adpt = getContext().getJobAdaptor();

        job = getContext().getJobAdaptor()
                .createJob(job);

        job = getContext().getJobRepository()
                .save(job);

        int taskCount = getContext()
                .getJobAdaptor()
                .getTaskCount(job);

        if (taskCount > 0) {
            ArrayList<Task> tasks = new ArrayList<>(taskCount);
            for (int i = 0; i < taskCount; i++) {
                Task task = new Task();
                getContext().getTaskRepository()
                        .save(task);
                tasks.add(task);
            }

            List<Task> news = getContext()
                    .getJobAdaptor()
                    .getTaskList(job, tasks);

            getContext().getTaskRepository()
                    .saveAll(news);
        }
        return job;
    }


    // @Scheduled
    protected void updateJob() {

        JobAdaptor adaptor = getContext().getJobAdaptor();

        activeJobs.forEach((job_id, job) -> {
            Status status = adaptor.getJobStatus(job);
            if (status != job.getStatus()) {
                job.setStatus(status);
                getContext().getJobRepository().save(job);
            }
        });
    }

    @Cacheable("jobs")
    public Job getJob(String jobid) {
        Job job = getContext().getJobRepository().findById(jobid)
                .orElseThrow();

        return job;
    }

    public Iterator<String> listJobIds() {
        return getContext().getJobRepository().findAll()
                .stream()
                .map(job -> job.getJobId())
                .iterator();
    }

    @Transactional
    public void deleteJob(String jobId) {
        Job job = getJob(jobId);

        getContext().getJobAdaptor()
                .deleteJob(job);

        getContext().getJobRepository().delete(job);
    }

    public void controlJob(String jobId, Action action, Map<String, String> config) {
        Job job = getJob(jobId);

        getContext().getJobAdaptor()
                .controlJob(job, action);
    }

    public Iterable<String> getJobLog(String jobId) {
        Job job = getJob(jobId);

        int count = getContext().getJobAdaptor()
                .getJobLogCount(job);

        new Iterator<String>() {

            int current = 0;

            @Override
            public boolean hasNext() {
                return current < count;
            }

            @Override
            public String next() {
                getContext().getJobAdaptor()
                        .getJobLog(job, 0, 0);
                return null;
            }
        };
        return null;
    }

    /******************************************************************************
     * TASK
     ******************************************************************************/

    @Transactional
    public void initializeTasks() {
        getContext().getTaskRepository()
                .deleteAll();

        int taskCount = getContext()
                .getTaskAdaptor()
                .getTaskCount();

        for (int i = 0; i < taskCount; i++) {
            Task task = new Task();

            Task newTask = getContext().getTaskAdaptor()
                    .getTaskByIndex(i, task);

            getContext().getTaskRepository()
                    .save(newTask);
        }
    }

    // @Scheduled
    private void updateTask() {

    }

    public Iterable<Task> listTask(String jobid) {

        return getContext().getTaskRepository()
                .findAllByJobid(jobid);
    }

    @Cacheable(value = "tasks")
    public Task getTask(String taskId) {

        return getContext()
                .getTaskRepository()
                .findById(taskId)
                .orElseThrow();
    }

    public void controlTask(String taskId, Action action, Map<String, String> config) {
        Task task = getTask(taskId);

        getContext().getTaskAdaptor()
                .controlTask(task, action, config);
    }

    public Iterator<String> getTaskLog(String taskid) {
        Task task = getTask(taskid);
        return getContext()
                .getTaskAdaptor()
                .getTaskLog(task, 0, 0);
    }

    public void invokeTask(String taskid) {
        throw new UnsupportedOperationException();
    }

    /******************************************************************************
     * DATA
     ******************************************************************************/
    @Transactional
    protected void initializeData() {
        Boolean isInitialized = getConfiguration().getBoolean(
                ConfigurationList.CONFIG_HIGHFLIP_DATA_IS_INITIALIZED,
                ConfigurationList.CONFIG_HIGHFLIP_DATA_IS_INITIALIZED_DEFAULT);

        if (isInitialized) {
            return;
        }

        int count = getContext().getDataAdaptor()
                .getDataCount();

        for (int i = 0; i < count; i++) {
            Data data = new Data();
            Data retData = getContext().getDataAdaptor()
                    .getDataByIndex(i, data);

            Data saveData = getContext().getDataRepository()
                    .save(retData);

            log.info("Initialize a data {}", saveData.getDataId());
        }

        getConfiguration().setBoolean(
                ConfigurationList.CONFIG_HIGHFLIP_DATA_IS_INITIALIZED,
                Boolean.TRUE);
    }

    /**
     * @return
     */
    public Iterable<String> listData() {
        return () -> getContext()
                .getDataRepository()
                .findAll()
                .stream()
                .map(d -> d.getDataId())
                .iterator();
    }

    /**
     * @param dataid
     * @return
     */
    @Cacheable("data")
    public Data getData(String dataid) {
        return getContext()
                .getDataRepository()
                .findById(dataid)
                .orElseThrow();
    }

    @Transactional
    public void deleteData(String dataid) {
        Data data = getData(dataid);

        getContext().getDataAdaptor()
                .deleteData(data);

        getContext()
                .getDataRepository()
                .delete(data);
    }

    public PushContext pushData(
        String name,
        String description,
        DataFormat format,
        List<Column> columns) {

        Data data = new Data();
        data.setName(name);
        data.setDescription(description);
        data.setColumns(columns);
        data.setFormat(format);
        getContext().getDataRepository().save(data);

        switch (format){
            case DENSE:
                return PushContext.createDense(
                        getContext().getDataAdaptor(), data);
            case SPARSE:
                return PushContext.createSparse(
                        getContext().getDataAdaptor(), data);
            default:
            case RAW:
                return PushContext.createRaw(
                        getContext().getDataAdaptor(), data);
        }
    }

    public InputStream pullDataRaw(String dataid, long offset, long size) {
        Data data = getData(dataid);

        return getContext().getDataAdaptor()
                .readDataRaw(data);
    }


    public Iterator<List<Object>> pullDataDense(String dataid, long offset, long size) {
        Data data = getData(dataid);

        return getContext().getDataAdaptor()
                .readDataDense(data);
    }

    public Iterator<List<KeyPair>> pullDataSparse(String dataid, long offset, long size) {
        Data data = getData(dataid);

        return getContext().getDataAdaptor()
                .readDataSparse(data);
    }

    /******************************************************************************
     * OPERATOR
     ******************************************************************************/
    @Transactional
    protected void initializeOperator() {
        Boolean isInitialized = getConfiguration().getBoolean(
                ConfigurationList.CONFIG_HIGHFLIP_OPERATOR_IS_INITIALIZED,
                ConfigurationList.CONFIG_HIGHFLIP_OPERATOR_IS_INITIALIZED_DEFAULT);

        if (isInitialized) {
            return;
        }

        int count = getContext()
                .getOperatorAdaptor()
                .getOperatorCount();

        for (int i = 0; i < count; i++) {
            Operator oper = new Operator();

            oper = getContext()
                    .getOperatorAdaptor()
                    .getOperatorByIndex(i, oper);

            oper = getContext()
                    .getOperatorRepository()
                    .save(oper);

            log.info("Initialize an operator {}", oper.getOperatorId());
        }

        getConfiguration().setBoolean(
                ConfigurationList.CONFIG_HIGHFLIP_OPERATOR_IS_INITIALIZED,
                Boolean.TRUE);
    }

    public Iterator<String> listOperator() {
        return getContext()
                .getOperatorRepository()
                .findAll()
                .stream()
                .map(a -> a.getOperatorId())
                .iterator();
    }

    public Operator getOperator(String operatorId) {
        return getContext()
                .getOperatorRepository()
                .findById(operatorId)
                .orElseThrow();
    }

    /******************************************************************************
     * PARTNER
     ******************************************************************************/
    @Transactional
    protected void initializePartners() {
        Boolean isInitialized = getConfiguration().getBoolean(
                ConfigurationList.CONFIG_HIGHFLIP_PARTNER_IS_INITIALIZED,
                ConfigurationList.CONFIG_HIGHFLIP_PARTNER_IS_INITIALIZED_DEFAULT);

        if (isInitialized) {
            return;
        }

        int count = getContext()
                .getPartnerAdaptor()
                .getPartnerCount();

        for (int i = 0; i < count; i++) {
            Partner partner = new Partner();

            partner = getContext()
                    .getPartnerAdaptor()
                    .getPartnerByIndex(i, partner);

            partner = getContext()
                    .getPartnerRepository()
                    .save(partner);

            log.info("Initialize a partner {}", partner.getPartnerId());
        }

        getConfiguration().setBoolean(
                ConfigurationList.CONFIG_HIGHFLIP_PARTNER_IS_INITIALIZED,
                Boolean.TRUE);
    }

    @Transactional
    public String createPartner(String name, String description) {
        Partner partner = new Partner();
        partner.setName(name);
        partner.setDescription(description);

        Partner retPartner = getContext().getPartnerAdaptor()
                .createPartner(partner);

        return getContext().getPartnerRepository()
                .save(retPartner)
                .getPartnerId();
    }


    public Partner getPartner(String partnerId) {
        return getContext()
                .getPartnerRepository()
                .findById(partnerId)
                .orElseThrow();
    }

    public Iterable<String> listPartner(int offset, int limit) {
        return () -> getContext()
                .getPartnerRepository()
                .findAll()
                .stream()
                .map(p -> p.getPartnerId())
                .iterator();
    }
}
