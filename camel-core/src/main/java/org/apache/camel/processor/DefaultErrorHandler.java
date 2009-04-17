/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.processor;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.impl.converter.AsyncProcessorTypeConverter;
import org.apache.camel.model.OnExceptionDefinition;
import org.apache.camel.processor.exceptionpolicy.ExceptionPolicyStrategy;
import org.apache.camel.util.AsyncProcessorHelper;
import org.apache.camel.util.ExchangeHelper;
import org.apache.camel.util.MessageHelper;
import org.apache.camel.util.ServiceHelper;

/**
 * Default error handler
 *
 * @version $Revision$
 */
public class DefaultErrorHandler extends ErrorHandlerSupport implements AsyncProcessor {
    private AsyncProcessor output;

    public DefaultErrorHandler(Processor output, ExceptionPolicyStrategy exceptionPolicyStrategy) {
        this.output = AsyncProcessorTypeConverter.convert(output);
        setExceptionPolicy(exceptionPolicyStrategy);
    }

    @Override
    public String toString() {
        return "DefaultErrorHandler[" + output + "]";
    }

    public boolean supportTransacted() {
        return false;
    }

    public void process(Exchange exchange) throws Exception {
        AsyncProcessorHelper.process(this, exchange);
    }

    public boolean process(final Exchange exchange, final AsyncCallback callback) {
        return output.process(exchange, new AsyncCallback() {
            public void done(boolean sync) {

                // do not handle transacted exchanges as this error handler does not support it
                boolean handle = true;
                if (exchange.isTransacted() && !supportTransacted()) {
                    handle = false;
                    if (log.isDebugEnabled()) {
                        log.debug("This error handler does not support transacted exchanges."
                            + " Bypassing this error handler: " + this + " for exchangeId: " + exchange.getExchangeId());
                    }
                }

                if (handle && exchange.getException() != null && !ExchangeHelper.isFailureHandled(exchange)) {
                    handleException(exchange);
                }
                callback.done(sync);
            }
        });
    }

    private void handleException(Exchange exchange) {
        Exception e = exchange.getException();

        // store the original caused exception in a property, so we can restore it later
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, e);

        // find the error handler to use (if any)
        OnExceptionDefinition exceptionPolicy = getExceptionPolicy(exchange, e);
        if (exceptionPolicy != null) {
            Predicate handledPredicate = exceptionPolicy.getHandledPolicy();

            Processor processor = exceptionPolicy.getErrorHandler();
            prepareExchangeBeforeOnException(exchange);
            if (processor != null) {
                deliverToFaultProcessor(exchange, processor);
            }
            prepareExchangeAfterOnException(exchange, handledPredicate);
        }
    }

    private void prepareExchangeBeforeOnException(Exchange exchange) {
        // okay lower the exception as we are handling it by onException
        if (exchange.getException() != null) {
            exchange.setException(null);
        }

        // clear rollback flags
        exchange.setProperty(Exchange.ROLLBACK_ONLY, null);

        // reset cached streams so they can be read again
        MessageHelper.resetStreamCache(exchange.getIn());
    }

    private boolean deliverToFaultProcessor(final Exchange exchange, final Processor failureProcessor) {
        AsyncProcessor afp = AsyncProcessorTypeConverter.convert(failureProcessor);
        return afp.process(exchange, new AsyncCallback() {
            public void done(boolean sync) {
            }
        });
    }

    private void prepareExchangeAfterOnException(Exchange exchange, Predicate handledPredicate) {
        if (handledPredicate == null || !handledPredicate.matches(exchange)) {
            if (log.isDebugEnabled()) {
                log.debug("This exchange is not handled so its marked as failed: " + exchange);
            }
            // exception not handled, put exception back in the exchange
            exchange.setException(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class));
        } else {
            if (log.isDebugEnabled()) {
                log.debug("This exchange is handled so its marked as not failed: " + exchange);
            }
            exchange.setProperty(Exchange.EXCEPTION_HANDLED, Boolean.TRUE);
        }
    }

    /**
     * Returns the output processor
     */
    public Processor getOutput() {
        return output;
    }

    protected void doStart() throws Exception {
        ServiceHelper.startServices(output);
    }

    protected void doStop() throws Exception {
        ServiceHelper.stopServices(output);
    }

}
