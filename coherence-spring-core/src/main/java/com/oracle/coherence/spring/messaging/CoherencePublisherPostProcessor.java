/*
 * Copyright (c) 2021 Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * https://oss.oracle.com/licenses/upl.
 */
package com.oracle.coherence.spring.messaging;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import com.oracle.coherence.common.base.Exceptions;
import com.oracle.coherence.spring.annotation.CoherencePublisher;
import com.oracle.coherence.spring.annotation.SessionName;
import com.oracle.coherence.spring.annotation.Topic;
import com.oracle.coherence.spring.annotation.Topics;
import com.tangosol.net.Coherence;
import com.tangosol.net.Session;
import com.tangosol.net.topic.Publisher;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.StringUtils;


/**
 * A {@link BeanFactoryPostProcessor} that processes classes and methods annotated
 * with {@literal @}{@link CoherencePublisher}.
 *
 * @author Vaso Putica
 * @since 3.0
 *
 */
public class CoherencePublisherPostProcessor implements BeanFactoryPostProcessor, AutoCloseable {
	protected final Log logger = LogFactory.getLog(getClass());

	private final Map<TopicKey, Publisher<Object>> publisherMap = new ConcurrentHashMap<>();

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		ClassPathScanningCandidateComponentProvider provider = getCoherencePublisherComponentProvider();

		for (BeanDefinition beanDef : provider.findCandidateComponents("")) {
			ProxyFactory pf = new ProxyFactory();
			try {
				pf.setInterfaces(Class.forName(beanDef.getBeanClassName()));
			}
			catch (ClassNotFoundException ex) {
				throw Exceptions.ensureRuntimeException(ex);
			}
			pf.addAdvice((MethodInterceptor) (invocation) -> {
				Method method = invocation.getMethod();

				CoherencePublisher coherencePublisher = AnnotationUtils.getAnnotation(method.getDeclaringClass(), CoherencePublisher.class);
				if (coherencePublisher == null) {
					throw new IllegalStateException("No @CoherencePublisher annotation present on class: " + beanDef.getBeanClassName());
				}

				String topicName = Utils.getFirstTopicName(method).orElse(null);
				String sessionName = getSessionName(method).orElse(Coherence.DEFAULT_NAME);

				CoherencePublisher methodCoherencePublisher = AnnotationUtils.getAnnotation(method, CoherencePublisher.class);
				if (methodCoherencePublisher != null) {
					coherencePublisher = methodCoherencePublisher;
				}

				String maxBlock = coherencePublisher.maxBlock();
				Duration maxBlockDuration = StringUtils.hasText(maxBlock)
						? Duration.parse(maxBlock)
						: null;

				Topic topic = AnnotationUtils.getAnnotation(method, Topic.class);
				if (topic != null) {
					topicName = topic.value();
				}

				Parameter[] parameters = method.getParameters();
				Object[] arguments = invocation.getArguments();
				int valueIndex = -1;

				for (int i = 0; i < parameters.length; i++) {
					Parameter parameter = parameters[i];
					MergedAnnotations parameterAnnotation = MergedAnnotations.from(parameter);
					if (parameterAnnotation.isPresent(Topics.class) || parameterAnnotation.isPresent(Topic.class)) {
						Object o = arguments[i];
						if (o != null) {
							topicName = o.toString();
						}
					}
					else if (valueIndex < 0 && parameterAnnotation.stream().count() == 0) {
						valueIndex = i;
					}
				}
				if (!StringUtils.hasLength(topicName)) {
					throw new RuntimeException("No topic specified for method: " + method);
				}

				if (valueIndex < 0) {
					throw new RuntimeException("No valid message body argument found for method: " + method);
				}
				Object value = arguments[valueIndex];

				Class<?> returnType = method.getReturnType();
				Publisher<Object> publisher = getPublisher(topicName, sessionName);

				boolean isReactiveReturnType = Publishers.isConvertibleToPublisher(returnType);
				boolean isReactiveValue = value != null && Publishers.isConvertibleToPublisher(value.getClass());

				if (isReactiveReturnType) {
					// return type is a reactive type
					Flux<Publisher.Status> flux = buildSendFlux(invocation, publisher, maxBlockDuration, value);
					return Publishers.convertPublisher(flux, returnType);
				}
				else {
					if (isReactiveValue) {
						if (!Publishers.isSingle(value.getClass())) {
							CompletableFuture<List<Publisher.Status>> completableFuture = new CompletableFuture<>();
							Flux<List<Publisher.Status>> sendFlux = buildSendFlux(invocation, publisher, maxBlockDuration, value).collectList().flux();
							sendFlux.subscribe(new SingleSubscriber<>(completableFuture, invocation));
							return completableFuture;
						}
						else {
							CompletableFuture<Publisher.Status> completableFuture = new CompletableFuture<>();
							Flux<Publisher.Status> sendFlux = buildSendFlux(invocation, publisher, maxBlockDuration, value);
							sendFlux.subscribe(new SingleSubscriber<>(completableFuture, invocation));
							return completableFuture;
						}
					}
					else {
						CompletableFuture<Publisher.Status> completableFuture = new CompletableFuture<>();
						publisher.publish(value).handle((status, exception) -> {
							if (exception != null) {
								completableFuture.completeExceptionally(wrapException(invocation, exception));
							}
							else {
								completableFuture.complete(status);
							}
							return null;
						});
						return completableFuture;
					}
				}
			});

			Object result = pf.getProxy();
			beanFactory.registerSingleton(beanDef.getBeanClassName(), result);
		}
	}

	private ClassPathScanningCandidateComponentProvider getCoherencePublisherComponentProvider() {
		ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				AnnotationMetadata metadata = beanDefinition.getMetadata();
				return metadata.isInterface();
			}
		};
		provider.addExcludeFilter(new RegexPatternTypeFilter(Pattern.compile("org\\.springframework\\..*")));
		provider.addExcludeFilter(new RegexPatternTypeFilter(Pattern.compile("java\\..*")));
		provider.addExcludeFilter(new RegexPatternTypeFilter(Pattern.compile("javax\\..*")));
		provider.addIncludeFilter(new AnnotationTypeFilter(CoherencePublisher.class, true, true));
		return provider;
	}

	private static RuntimeException wrapException(MethodInvocation invocation, Throwable exception) {
		return new RuntimeException(
				"Exception sending message for method [" + invocation.getMethod() + "]: " + exception.getMessage(), exception
		);
	}

	@Override
	public void close() {
		for (Map.Entry<TopicKey, Publisher<Object>> entry : this.publisherMap.entrySet()) {
			Publisher<Object> publisher = entry.getValue();
			try {
				publisher.flush().get(1, TimeUnit.MINUTES);
			}
			catch (Throwable throwable) {
				this.logger.error("Error flushing publisher", throwable);
			}

			try {
				publisher.close();
			}
			catch (Throwable throwable) {
				this.logger.error("Error closing publisher", throwable);
			}
		}
	}

	@Nonnull
	private Publisher<Object> getPublisher(String topicName, String sessionName) {
		TopicKey key = new TopicKey(topicName, sessionName);
		return this.publisherMap.compute(key, (k, publisher) -> {
			if (publisher != null) {
				return publisher;
			}
			final Session session = Coherence.findSession(sessionName)
					.orElseThrow(() -> new IllegalStateException(String.format("No Session is configured with name '%s'.", sessionName)));
			return session.getTopic(topicName).createPublisher();
		});
	}

	protected Optional<String> getSessionName(Method method) {
		SessionName sessionNameAnnotation = AnnotationUtils.getAnnotation(method, SessionName.class);
		if (sessionNameAnnotation != null) {
			return Optional.of(sessionNameAnnotation.value());
		}
		return Optional.empty();
	}

	private Flux<Publisher.Status> buildSendFlux(
			MethodInvocation context,
			Publisher<Object> publisher,
			Duration maxBlock,
			Object value) {

		Flux<?> valueFlux = Publishers.convertPublisher(value, Flux.class);
		Flux<Publisher.Status> sendFlux = valueFlux.flatMap((o) -> Flux.create((emitter) -> publisher.publish(o).handle((status, exception) -> {
							if (exception != null) {
								emitter.error(wrapException(context, exception));
							}
							else {
								if (status != null) {
									emitter.next(status);
								}
								emitter.complete();
							}
							return null;
						}), FluxSink.OverflowStrategy.BUFFER));

		if (maxBlock != null) {
			sendFlux.timeout(maxBlock);
		}
		return sendFlux;
	}

	private static final class SingleSubscriber<T> implements Subscriber<T> {
		private final CompletableFuture<T> completableFuture;
		private final MethodInvocation invocation;
		private T status;

		SingleSubscriber(CompletableFuture<T> completableFuture, MethodInvocation invocation) {
			this.completableFuture = completableFuture;
			this.invocation = invocation;
		}

		@Override
		public void onSubscribe(Subscription s) {
			s.request(1);
		}

		@Override
		public void onNext(T t) {
			this.status = t;
		}

		@Override
		public void onError(Throwable t) {
			this.completableFuture.completeExceptionally(wrapException(this.invocation, t));
		}

		@Override
		public void onComplete() {
			this.completableFuture.complete(this.status);
		}
	}
}
