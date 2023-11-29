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

import org.openjdk.jmc.rjmx.ConnectionException;

import io.netty.handler.codec.http.HttpResponseStatus;
import jakarta.persistence.NoResultException;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.projectnessie.cel.tools.ScriptException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

public class ExceptionMappers {
    @ServerExceptionMapper
    public RestResponse<Void> mapNoResultException(NoResultException ex) {
        return RestResponse.notFound();
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapConstraintViolationException(ConstraintViolationException ex) {
        return RestResponse.status(HttpResponseStatus.BAD_REQUEST.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapValidationException(jakarta.validation.ValidationException ex) {
        return RestResponse.status(HttpResponseStatus.BAD_REQUEST.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapScriptException(ScriptException ex) {
        return RestResponse.status(HttpResponseStatus.BAD_REQUEST.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapNoSuchKeyException(NoSuchKeyException ex) {
        return RestResponse.status(HttpResponseStatus.NOT_FOUND.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapIllegalArgumentException(IllegalArgumentException exception) {
        return RestResponse.status(HttpResponseStatus.BAD_REQUEST.code());
    }

    @ServerExceptionMapper
    public RestResponse<Void> mapJmxConnectionException(ConnectionException exception) {
        return RestResponse.status(HttpResponseStatus.BAD_GATEWAY.code());
    }
}
