/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2002-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.eventd;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opennms.core.concurrent.LogPreservingThreadFactory;
import org.opennms.core.logging.Logging;
import org.opennms.netmgt.eventd.processor.expandable.EventTemplate;
import org.opennms.netmgt.events.api.EventHandler;
import org.opennms.netmgt.events.api.EventIpcBroadcaster;
import org.opennms.netmgt.events.api.EventIpcManager;
import org.opennms.netmgt.events.api.EventListener;
import org.opennms.netmgt.events.api.EventProxyException;
import org.opennms.netmgt.xml.event.Event;
import org.opennms.netmgt.xml.event.Events;
import org.opennms.netmgt.xml.event.Log;
import org.springframework.util.Assert;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;

/**
 * An implementation of the EventIpcManager interface that can be used to
 * communicate between services in the same JVM
 *
 * @author <A HREF="mailto:sowmya@opennms.org">Sowmya Nataraj </A>
 * @author <A HREF="http://www.opennms.org">OpenNMS.org </A>
 */
public class EventIpcManagerDefaultImpl extends AbstractVerticle implements EventIpcManager, EventIpcBroadcaster {

	// private static final Logger LOG =
	// LoggerFactory.getLogger(EventIpcManagerDefaultImpl.class);

	public static class DiscardTrapsAndSyslogEvents implements RejectedExecutionHandler {
		/**
		 * Creates a <tt>DiscardOldestPolicy</tt> for the given executor.
		 */
		public DiscardTrapsAndSyslogEvents() {
		}

		/**
		 * Obtains and ignores the next task that the executor would otherwise execute,
		 * if one is immediately available, and then retries execution of task r, unless
		 * the executor is shut down, in which case task r is instead discarded.
		 * 
		 * @param r
		 *            the runnable task requested to be executed
		 * @param e
		 *            the executor attempting to execute this task
		 */
		@Override
		public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
			if (!e.isShutdown()) {
				e.getQueue().poll();
				e.execute(r);
			}
		}
	}

	/**
	 * Hash table of list of event listeners keyed by event UEI
	 */
	private Map<String, List<EventListener>> m_ueiListeners = new HashMap<String, List<EventListener>>();

	/**
	 * The list of event listeners interested in all events
	 */
	private List<EventListener> m_listeners = new ArrayList<EventListener>();

	/**
	 * Hash table of event listener threads keyed by the listener's id
	 */
	private Map<String, EventListenerExecutor> m_listenerThreads = new HashMap<String, EventListenerExecutor>();

	/**
	 * The thread pool handling the events
	 */
	private ExecutorService m_eventHandlerPool;

	private static EventHandler m_eventHandler;

	private Integer m_handlerPoolSize;

	private Integer m_handlerQueueLength;

	private AtomicBoolean running;

	private ExecutorService backgroundConsumer;

	private static final String EVENTD_CONSUMER_ADDRESS = "eventd.message.consumer";

	private static final String DEFAULT_EVENTD_CONSUMER_ADDRESS = "default.eventd.message.consumer";

	private EventBus eventIpcEventBus;

	/**
	 * A thread dedicated to each listener. The events meant for each listener is
	 * added to an execution queue when the 'sendNow()' is called. The
	 * ListenerThread reads events off of this queue and sends them to the
	 * appropriate listener.
	 */
	private static class EventListenerExecutor {
		/**
		 * Listener to which this thread is dedicated
		 */
		private final EventListener m_listener;

		/**
		 * The thread that is running this runnable.
		 */
		private final ExecutorService m_delegateThread;

		/**
		 * Constructor
		 */
		EventListenerExecutor(EventListener listener, Integer handlerQueueLength) {
			m_listener = listener;
			// You could also do Executors.newSingleThreadExecutor() here
			m_delegateThread = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
					handlerQueueLength == null ? new LinkedBlockingQueue<Runnable>()
							: new LinkedBlockingQueue<Runnable>(handlerQueueLength),
					// This ThreadFactory will ensure that the log prefix of the calling thread
					// is used for all events that this listener handles. Therefore, if Notifd
					// registers for an event then all logs for handling that event will end up
					// inside notifd.log.
					new LogPreservingThreadFactory(m_listener.getName(), 1), new RejectedExecutionHandler() {
						@Override
						public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
							// LOG.warn("Listener {}'s event queue is full, discarding event",
							// m_listener.getName());
						}
					});
		}

		public CompletableFuture<Void> addEvent(final Event event) {
			return CompletableFuture.runAsync(new Runnable() {
				@Override
				public void run() {
					try {
						// if (LOG.isDebugEnabled())
						// LOG.debug("run: calling onEvent on {} for event {}", m_listener.getName(),
						// event.toStringSimple());

						// Make sure we restore our log4j logging prefix after onEvent is called
						Map<String, String> mdc = Logging.getCopyOfContextMap();
						try {
							m_listener.onEvent(event);
						} finally {
							Logging.setContextMap(mdc);
						}
					} catch (Throwable t) {
						// LOG.warn("run: an unexpected error occured during ListenerThread {}",
						// m_listener.getName(), t);
					}
				}
			}, m_delegateThread);
		}

		/**
		 * Stops the execution of this listener.
		 */
		public void stop() {
			m_delegateThread.shutdown();
		}
	}

	public EventIpcManagerDefaultImpl() {
	}

	public static void main(String[] args) {
		m_eventHandler = new DefaultEventHandlerImpl(new MetricRegistry());
		System.setProperty("opennms.home", "src/test/resources");
		// org.apache.log4j.Logger logger4j = org.apache.log4j.Logger.getRootLogger();
		// logger4j.setLevel(org.apache.log4j.Level.toLevel("ERROR"));
		DeploymentOptions deployment = new DeploymentOptions();
		deployment.setWorker(true);
		deployment.setWorkerPoolSize(Integer.MAX_VALUE);
		deployment.setMultiThreaded(true);

	}

	@Override
	public void start() throws Exception {
		running = new AtomicBoolean(true);

		eventIpcEventBus = vertx.eventBus();

		backgroundConsumer = Executors.newSingleThreadExecutor();
		backgroundConsumer.submit(() -> {
			try {

				consume();

			} catch (Exception ex) {
				String error = "Failed to startup";
			}
		});
	}

	private void consume() {
		while (running.get()) {
			try {
				MessageConsumer<String> eventIpcConsumer = eventIpcEventBus.consumer(EVENTD_CONSUMER_ADDRESS);
				eventIpcConsumer.handler(message -> {
					// eventIpcEventBus.send(DEFAULT_EVENTD_CONSUMER_ADDRESS,
					// DefaultEventHandlerImpl.getEventdLog());
				});
			} catch (Exception ex) {
			}
		}
	}

	/** {@inheritDoc} */
	@Override
	public void send(Event event) throws EventProxyException {
		sendNow(event);
	}

	/**
	 * <p>
	 * send
	 * </p>
	 *
	 * @param eventLog
	 *            a {@link org.opennms.netmgt.xml.event.Log} object.
	 * @throws org.opennms.netmgt.events.api.EventProxyException
	 *             if any.
	 */
	@Override
	public void send(Log eventLog) throws EventProxyException {
		sendNow(eventLog);
	}

	/**
	 * {@inheritDoc}
	 *
	 * Called by a service to send an event to other listeners.
	 */
	@Override
	public void sendNow(Event event) {
		Assert.notNull(event, "event argument cannot be null");

		Events events = new Events();
		events.addEvent(event);

		Log eventLog = new Log();
		eventLog.setEvents(events);

		sendNow(eventLog);
	}

	/**
	 * Called by a service to send a set of events to other listeners. Creates a new
	 * event handler for the event log and queues it to the event handler thread
	 * pool.
	 *
	 * @param eventLog
	 *            a {@link org.opennms.netmgt.xml.event.Log} object.
	 */
	@Override
	public void sendNow(Log eventLog) {
		Assert.notNull(eventLog, "eventLog argument cannot be null");

		// if (LOG.isDebugEnabled())
		// LOG.debug("sending: {}", eventLog);

		try {
			m_eventHandlerPool.execute(m_eventHandler.createRunnable(eventLog));
		} catch (RejectedExecutionException e) {
			// LOG.warn("Unable to queue event log to the event handler pool queue", e);
			throw e;
		}
	}

	@Override
	public void sendNowSync(Event event) {
		Objects.requireNonNull(event);

		Events events = new Events();
		events.addEvent(event);

		Log eventLog = new Log();
		eventLog.setEvents(events);

		sendNowSync(eventLog);
	}

	@Override
	public void sendNowSync(Log eventLog) {
		Objects.requireNonNull(eventLog);
		// Create the runnable and invoke it using the current thread
		// Also set the logging prefix to ensure that the log messages are
		// properly routed to eventd's log file
		m_eventHandler.createRunnable(eventLog, true).run();
		// Logging.withPrefix(Eventd.LOG4J_CATEGORY,
		// m_eventHandler.createRunnable(eventLog, true));
	}

	// public void sendNowSync1(Event eventLog) {
	// Objects.requireNonNull(eventLog);
	// // Create the runnable and invoke it using the current thread
	// // Also set the logging prefix to ensure that the log messages are
	// // properly routed to eventd's log file
	// m_eventHandler.createRunnable(eventLog, true).run();
	// // Logging.withPrefix(Eventd.LOG4J_CATEGORY,
	// // m_eventHandler.createRunnable(eventLog, true));
	// }

	@Override
	public void broadcastNow(Event event, boolean synchronous) {
		// if (LOG.isDebugEnabled()) {
		// LOG.debug("Event ID {} to be broadcasted: {}", event.getDbid(),
		// event.getUei());
		// }
		//
		// if (LOG.isDebugEnabled() && m_listeners.isEmpty()) {
		// LOG.debug("No listeners interested in all events");
		// }

		List<CompletableFuture<Void>> listenerFutures = new ArrayList<>();

		// Send to listeners interested in receiving all events
		for (EventListener listener : m_listeners) {
			listenerFutures.add(queueEventToListener(event, listener));
		}

		if (event.getUei() == null) {
			// if (LOG.isDebugEnabled()) {
			// LOG.debug("Event ID {} does not have a UEI, so skipping UEI matching",
			// event.getDbid());
			// }
			return;
		}
		// System.out.println("\n"+new EventWrapper(event));

		/*
		 * Send to listeners who are interested in this event UEI. Loop to attempt
		 * partial wild card "directory" matches.
		 */
		Set<EventListener> sentToListeners = new HashSet<EventListener>();
		for (String uei = event.getUei(); uei.length() > 0;) {
			if (m_ueiListeners.containsKey(uei)) {
				for (EventListener listener : m_ueiListeners.get(uei)) {
					if (!sentToListeners.contains(listener)) {
						listenerFutures.add(queueEventToListener(event, listener));
						sentToListeners.add(listener);
					}
				}
			}

			// Try wild cards: Find / before last character
			int i = uei.lastIndexOf("/", uei.length() - 2);
			if (i > 0) {
				// Split at "/", including the /
				uei = uei.substring(0, i + 1);
			} else {
				// No more wild cards to match
				break;
			}
		}

		if (sentToListeners.isEmpty()) {
			// if (LOG.isDebugEnabled()) {
			// LOG.debug("No listener interested in event ID {}: {}", event.getDbid(),
			// event.getUei());
			// }
		}

		// If synchronous...
		if (synchronous) {
			// Wait for all of the listeners to complete before returning
			CompletableFuture.allOf(listenerFutures.toArray(new CompletableFuture[0])).join();
		}
	}

	private CompletableFuture<Void> queueEventToListener(Event event, EventListener listener) {
		return m_listenerThreads.get(listener.getName()).addEvent(event);
	}

	/**
	 * {@inheritDoc}
	 *
	 * Register an event listener that is interested in all events. Removes this
	 * listener from any UEI-specific matches.
	 */
	@Override
	public synchronized void addEventListener(EventListener listener) {
		Assert.notNull(listener, "listener argument cannot be null");

		createListenerThread(listener);

		addMatchAllForListener(listener);

		// Since we have a match-all listener, remove any specific UEIs
		for (String uei : m_ueiListeners.keySet()) {
			removeUeiForListener(uei, listener);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * Register an event listener interested in the UEIs in the passed list.
	 */
	@Override
	public synchronized void addEventListener(EventListener listener, Collection<String> ueis) {
		Assert.notNull(listener, "listener argument cannot be null");
		Assert.notNull(ueis, "ueilist argument cannot be null");

		if (ueis.isEmpty()) {
			// LOG.warn("Not adding event listener {} because the ueilist argument contains
			// no entries",
			// listener.getName());
			return;
		}

		// if (LOG.isDebugEnabled()) {
		// LOG.debug("Adding event listener {} for UEIs: {}", listener.getName(),
		// StringUtils.collectionToCommaDelimitedString(ueis));
		// }

		createListenerThread(listener);

		for (String uei : ueis) {
			addUeiForListener(uei, listener);
		}

		// Since we have a UEI-specific listener, remove the match-all listener
		removeMatchAllForListener(listener);
	}

	/**
	 * Register an event listener interested in the passed UEI.
	 *
	 * @param listener
	 *            a {@link org.opennms.netmgt.events.api.EventListener} object.
	 * @param uei
	 *            a {@link java.lang.String} object.
	 */
	@Override
	public synchronized void addEventListener(EventListener listener, String uei) {
		Assert.notNull(listener, "listener argument cannot be null");
		Assert.notNull(uei, "uei argument cannot be null");

		addEventListener(listener, Collections.singletonList(uei));
	}

	/**
	 * {@inheritDoc}
	 *
	 * Removes a registered event listener. The UEI list indicates the list of
	 * events the listener is no more interested in.
	 *
	 * <strong>Note: </strong>The listener thread for this listener is not stopped
	 * until the 'removeEventListener(EventListener listener)' method is called.
	 */
	@Override
	public synchronized void removeEventListener(EventListener listener, Collection<String> ueis) {
		Assert.notNull(listener, "listener argument cannot be null");
		Assert.notNull(ueis, "ueilist argument cannot be null");

		for (String uei : ueis) {
			removeUeiForListener(uei, listener);
		}
	}

	/**
	 * Removes a registered event listener. The UEI indicates one the listener is no
	 * more interested in.
	 *
	 * <strong>Note: </strong>The listener thread for this listener is not stopped
	 * until the 'removeEventListener(EventListener listener)' method is called.
	 *
	 * @param listener
	 *            a {@link org.opennms.netmgt.events.api.EventListener} object.
	 * @param uei
	 *            a {@link java.lang.String} object.
	 */
	@Override
	public synchronized void removeEventListener(EventListener listener, String uei) {
		Assert.notNull(listener, "listener argument cannot be null");
		Assert.notNull(uei, "uei argument cannot be null");

		removeUeiForListener(uei, listener);
	}

	/**
	 * {@inheritDoc}
	 *
	 * Removes a registered event listener.
	 *
	 * <strong>Note: </strong>Only this method stops the listener thread for the
	 * listener passed.
	 */
	@Override
	public synchronized void removeEventListener(EventListener listener) {
		Assert.notNull(listener, "listener argument cannot be null");

		removeMatchAllForListener(listener);

		for (String uei : m_ueiListeners.keySet()) {
			removeUeiForListener(uei, listener);
		}

		// stop and remove the listener thread for this listener
		if (m_listenerThreads.containsKey(listener.getName())) {
			m_listenerThreads.get(listener.getName()).stop();

			m_listenerThreads.remove(listener.getName());
		}
	}

	/**
	 * Create a new queue and listener thread for this listener if one does not
	 * already exist.
	 */
	private void createListenerThread(EventListener listener) {
		if (m_listenerThreads.containsKey(listener.getName())) {
			return;
		}

		EventListenerExecutor listenerThread = new EventListenerExecutor(listener, m_handlerQueueLength);
		m_listenerThreads.put(listener.getName(), listenerThread);
	}

	/**
	 * Add to uei listeners.
	 */
	private void addUeiForListener(String uei, EventListener listener) {
		// Ensure there is a list for this UEI
		if (!m_ueiListeners.containsKey(uei)) {
			m_ueiListeners.put(uei, new ArrayList<EventListener>());
		}

		List<EventListener> listenersList = m_ueiListeners.get(uei);
		if (!listenersList.contains(listener)) {
			listenersList.add(listener);
		}
	}

	/**
	 * Remove UEI for this listener.
	 */
	private void removeUeiForListener(String uei, EventListener listener) {
		if (m_ueiListeners.containsKey(uei)) {
			m_ueiListeners.get(uei).remove(listener);
		}
	}

	/**
	 * Add listener to list of listeners listening for all events.
	 */
	private boolean addMatchAllForListener(EventListener listener) {
		return m_listeners.add(listener);
	}

	/**
	 * Remove from list of listeners listening for all events.
	 */
	private boolean removeMatchAllForListener(EventListener listener) {
		return m_listeners.remove(listener);
	}

	/**
	 * <p>
	 * afterPropertiesSet
	 * </p>
	 */
	// public void afterPropertiesSet() {
	// Assert.state(m_eventHandlerPool == null, "afterPropertiesSet() has already
	// been called");
	//
	// Assert.state(m_eventHandler != null, "eventHandler not set");
	// Assert.state(m_handlerPoolSize != null, "handlerPoolSize not set");
	//
	// final LinkedBlockingQueue<Runnable> workQueue = m_handlerQueueLength == null
	// ? new LinkedBlockingQueue<>()
	// : new LinkedBlockingQueue<>(m_handlerQueueLength);
	// m_registry.remove("eventlogs.queued");
	// m_registry.register("eventlogs.queued", new Gauge<Integer>() {
	// @Override
	// public Integer getValue() {
	// return workQueue.size();
	// }
	// });
	//
	// Logging.withPrefix(Eventd.LOG4J_CATEGORY, new Runnable() {
	//
	// @Override
	// public void run() {
	// /**
	// * Create a fixed-size thread pool. The number of threads can be configured by
	// * using the "receivers" attribute in the config. The queue length for the
	// pool
	// * can be configured with the "queueLength" attribute in the config.
	// */
	// m_eventHandlerPool = new ThreadPoolExecutor(m_handlerPoolSize,
	// m_handlerPoolSize, 0L,
	// TimeUnit.MILLISECONDS, workQueue, new LogPreservingThreadFactory(
	// EventIpcManagerDefaultImpl.class.getSimpleName(), m_handlerPoolSize));
	// }
	//
	// });
	// }

	/**
	 * <p>
	 * getEventHandler
	 * </p>
	 *
	 * @return a {@link org.opennms.netmgt.events.api.EventHandler} object.
	 */
	public EventHandler getEventHandler() {
		return m_eventHandler;
	}

	/**
	 * <p>
	 * setEventHandler
	 * </p>
	 *
	 * @param eventHandler
	 *            a {@link org.opennms.netmgt.events.api.EventHandler} object.
	 */
	public void setEventHandler(EventHandler eventHandler) {
		m_eventHandler = eventHandler;
	}

	/**
	 * <p>
	 * getHandlerPoolSize
	 * </p>
	 *
	 * @return a int.
	 */
	public int getHandlerPoolSize() {
		return m_handlerPoolSize;
	}

	/**
	 * <p>
	 * setHandlerPoolSize
	 * </p>
	 *
	 * @param handlerPoolSize
	 *            a int.
	 */
	public void setHandlerPoolSize(int handlerPoolSize) {
		Assert.state(m_eventHandlerPool == null,
				"handlerPoolSize property cannot be set after afterPropertiesSet() is called");

		m_handlerPoolSize = handlerPoolSize;
	}

	/**
	 * <p>
	 * getHandlerQueueLength
	 * </p>
	 *
	 * @return a int.
	 */
	public int getHandlerQueueLength() {
		return m_handlerQueueLength;
	}

	/**
	 * <p>
	 * setHandlerQueueLength
	 * </p>
	 *
	 * @param size
	 *            a int.
	 */
	public void setHandlerQueueLength(int size) {
		Assert.state(m_eventHandlerPool == null,
				"handlerQueueLength property cannot be set after afterPropertiesSet() is called");
		m_handlerQueueLength = size;
	}

	@Override
	public boolean hasEventListener(final String uei) {
		if (this.m_ueiListeners.containsKey(uei)) {
			return this.m_ueiListeners.get(uei).size() > 0;
		} else {
			return false;
		}
	}
}
