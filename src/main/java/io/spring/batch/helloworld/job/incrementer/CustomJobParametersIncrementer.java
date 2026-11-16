package io.spring.batch.helloworld.job.incrementer;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.lang.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;

public class CustomJobParametersIncrementer implements JobParametersIncrementer {

    static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public JobParameters getNext(@Nullable JobParameters parameters) {
        String id = dateFormat.format(new Date());

        return new JobParametersBuilder()
                .addString("run.id", id).toJobParameters();
    }
}
