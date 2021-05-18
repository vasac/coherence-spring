/*
 * Copyright (c) 2021 Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.oracle.coherence.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Scope;
import javax.inject.Singleton;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * An introduction advice that automatically implements interfaces and abstract classes and
 * creates {@link com.tangosol.net.topic.Publisher} instances.
 *
 * @author Vaso Putica
 * @see com.oracle.coherence.spring.messaging.CoherencePublisherPostProcessor
 * @since 3.0
 */
@Documented
@Retention(RUNTIME)
@Scope
@Singleton
public @interface CoherencePublisher {
	/**
	 * The maximum duration to block synchronous send operations.
	 *
	 * @return The timeout
	 */
	String maxBlock() default "";
}
