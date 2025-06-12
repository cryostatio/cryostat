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
package io.cryostat;

import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import io.cryostat.util.EntityExistsException;

import com.nimbusds.jwt.proc.BadJWTException;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.smallrye.mutiny.TimeoutException;
import jakarta.inject.Inject;
import jakarta.persistence.NoResultException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.RestResponse.ResponseBuilder;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.projectnessie.cel.tools.ScriptException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Map uncaught exceptions at the endpoint level to REST responses. For example, without a
 * corresponding exception mapper, an uncaught jakarta.persistence.NoResultException would result in
 * the web server responding with an HTTP 500 Internal Server Error (the default behaviour for all
 * uncaught exceptions). Defining a mapper for NoResultException allows the global behaviour to be
 * overridden to an HTTP 404 Not Found response. Individual endpoints may still use standard
 * try-catch handling to deal with NoResultExceptions in other ways as needed.
 */
public class ExceptionMappers {

    @Inject Logger logger;

    @ServerExceptionMapper
    public RestResponse<Void> mapNoResultException(NoResultException ex) {
        return RestResponse.notFound();
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapNoSuchElementException(NoSuchElementException ex) {
        return RestResponse.notFound();
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapNoSuchBucketException(NoSuchBucketException ex) {
        logger.error(ex);
        return RestResponse.status(HttpResponseStatus.BAD_GATEWAY.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapS3Exception(S3Exception ex) {
        logger.error(ex);
        switch (ex.statusCode()) {
            case 404:
                return RestResponse.notFound();
        }
        return RestResponse.status(HttpResponseStatus.BAD_GATEWAY.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapConstraintViolationException(ConstraintViolationException ex) {
        logger.warn(ex);
        return RestResponse.status(HttpResponseStatus.BAD_REQUEST.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapValidationException(jakarta.validation.ValidationException ex) {
        logger.warn(ex);
        return RestResponse.status(HttpResponseStatus.BAD_REQUEST.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapScriptException(ScriptException ex) {
        logger.warn(ex);
        return RestResponse.status(HttpResponseStatus.BAD_REQUEST.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapNoSuchKeyException(NoSuchKeyException ex) {
        logger.warn(ex);
        return RestResponse.status(HttpResponseStatus.NOT_FOUND.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapIllegalArgumentException(IllegalArgumentException ex) {
        logger.warn(ex);
        return RestResponse.status(HttpResponseStatus.BAD_REQUEST.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapMutinyTimeoutException(TimeoutException ex) {
        logger.warn(ex);
        return RestResponse.status(HttpResponseStatus.GATEWAY_TIMEOUT.code());
    }

    @ServerExceptionMapper
    public RestResponse<Object> mapEntityExistsException(EntityExistsException ex) {
        logger.warn(ex);
        return ResponseBuilder.create(HttpResponseStatus.CONFLICT.code())
                .entity(ex.getMessage())
                .build();
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapBadJwtException(BadJWTException ex) {
        logger.warn(ex);
        return RestResponse.status(HttpResponseStatus.UNAUTHORIZED.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapCompletionException(CompletionException ex) throws Throwable {
        logger.warn(ex);
        throw ExceptionUtils.getRootCause(ex);
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapExecutionException(ExecutionException ex) throws Throwable {
        logger.warn(ex);
        throw ExceptionUtils.getRootCause(ex);
    }
}
