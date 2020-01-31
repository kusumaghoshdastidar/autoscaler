/*
 * Copyright 2015-2020 Micro Focus or one of its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hpe.caf.autoscale.endpoint;

/**
 * Exception class to help report problems in the httpclient.
 */
public class HttpClientException extends RuntimeException
{

    public HttpClientException(String message)
    {
        super(message);
    }

    public HttpClientException(String message, Throwable throwable)
    {
        super(message, throwable);
    }

}