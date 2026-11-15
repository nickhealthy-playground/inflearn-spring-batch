package io.spring.batch.helloworld.job.validator;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.lang.Nullable;

public class CustomJobParametersValidator implements JobParametersValidator {
    @Override
    public void validate(@Nullable JobParameters parameters) throws JobParametersInvalidException {
        if (parameters.getString("name") == null) {
            throw new  JobParametersInvalidException("name parameter is missing");
        }
    }
}
