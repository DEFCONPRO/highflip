package com.webank.ai.fate.client;

import com.webank.ai.fate.client.form.JobQueryResponseForm;
import com.webank.ai.fate.client.form.JsonResultForm;
import com.webank.ai.fate.client.form.ResultForm;
import feign.Feign;
import feign.Headers;
import feign.Param;
import feign.Request;
import feign.RequestLine;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Headers("Content-Type: application/json")
public interface FateClient {

    static FateClient connect(String url) {
        return Feign.builder()
                .decoder(new JacksonDecoder())
                .encoder(new JacksonEncoder())
                .options(new Request.Options(
                        10, TimeUnit.SECONDS,
                        60, TimeUnit.SECONDS,
                        true))
                .target(FateClient.class, url);
    }

    @RequestLine("POST /v1/version/get")
    JsonResultForm version();

    @RequestLine("POST /v1/version/get")
    JsonResultForm versionGet(@Param("module") String module);

    @RequestLine("POST /v1/job/submit")
    String jobSumbit(@Param("job_dsl") String dsl,
                     @Param("job_runtime_conf") String conf);


    @RequestLine("POST /v1/job/list/job")
    JsonResultForm jobList(@Param("limit") Integer limit);

    @RequestLine("POST /v1/job/stop")
    void jobStop(@Param("job_id") String jobId);

    @RequestLine("POST /v1/job/query")
    ResultForm<List<JobQueryResponseForm>> jobQuery(@Param("job_id") String jobId);


    /**
     *
     * @param head
     * @param id_delimiter
     * @param partition
     * @param table_name
     * @param namespace
     * @param storage_engine
     * @param destory
     * @param extend_sid
     * @return
     */
    @Headers("Content-Type: application/octet-stream")
    @RequestLine("POST /data/upload")
    JsonResultForm dataUpload(
            @Param("file") File file,
            Integer head,
            String id_delimiter,
            Integer partition,
            String table_name,
            String namespace,
            String storage_engine,
            Integer destory,
            Integer extend_sid);


    @RequestLine("POST /data/download")
    JsonResultForm dataDownload(
            String table_name,
            String namespace,
            String output_path,
            String delimiter);
}
