/* Generated by Streams Studio: March 26, 2014 2:09:26 PM EDT */
/*******************************************************************************
 * Copyright (C) 2015, MOHAMED-ALI SAID
 * All Rights Reserved
 *******************************************************************************/
package com.ibm.streamsx.messaging.rabbitmq;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeoutException;

import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.logging.TraceLevel;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streams.operator.state.ConsistentRegionContext;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import java.util.logging.Logger;

/**
 * This operator was originally contributed by Mohamed-Ali Said @saidmohamedali
 */
@OutputPorts(@OutputPortSet(cardinality = 1, optional = false, description = "Messages received from Kafka are sent on this output port."))
@PrimitiveOperator(name = "RabbitMQSource", description = RabbitMQSource.DESC)
public class RabbitMQSource extends RabbitBaseOper {

	private List<String> routingKeys = new ArrayList<String>();
	
	private final Logger trace = Logger.getLogger(RabbitBaseOper.class
			.getCanonicalName());
	
	private Thread processThread;
	private String queueName = "";
	
	//consistent region checks
	@ContextCheck(compile = true)
	public static void checkInConsistentRegion(OperatorContextChecker checker) {
		ConsistentRegionContext consistentRegionContext = 
				checker.getOperatorContext().getOptionalContext(ConsistentRegionContext.class);
		if (consistentRegionContext != null){
			checker.setInvalidContext("This operator cannot be the start of a consistent region.", null);
		}
	}
	
	@Override
	public synchronized void initialize(OperatorContext context)
			throws Exception {
		
		super.initialize(context);
		super.initSchema(getOutput(0).getStreamSchema());
		trace.log(TraceLevel.INFO, this.getClass().getName() + "Operator " + context.getName()
				+ " initializing in PE: " + context.getPE().getPEId()
				+ " in Job: " + context.getPE().getJobId());

		initRabbitChannel();
		// produce tuples returns immediately, but we don't want ports to close
		createAvoidCompletionThread();

		processThread = getOperatorContext().getThreadFactory().newThread(
				new Runnable() {

					@Override
					public void run() {
						try {
							produceTuples();
							// rabbitMQWrapper.Consume();
						} catch (Exception e) {
							e.printStackTrace(); // Logger.getLogger(this.getClass()).error("Operator error",
													// e);
						}
					}

				});

		processThread.setDaemon(false);
	}

	private void initRabbitChannel() throws IOException {
		
		boolean createdQueue = initializeQueue(connection);
		
		//Only want to bind to routing keys or exchanges if we created the queue
		//We don't want to modify routing keys of existing queues. 
		if (createdQueue){
			if (routingKeys.isEmpty())
				routingKeys.add("");//add a blank routing key

			//You can't bind to a default exchange
			if (!usingDefaultExchange){
				for (String routingKey : routingKeys){
					channel.queueBind(queueName, exchangeName, routingKey);
					trace.log(TraceLevel.INFO, "Queue: " + queueName + " Exchange: " + exchangeName + " RoutingKey " + routingKey);
				}
			}
		} else {
			if (!routingKeys.isEmpty()) {
				trace.log(
						TraceLevel.WARNING,
						"Queue already exists, therefore specified routing key arguments have been ignored. "
								+ "To use specified routing key/keys, you must either configure the existing queue "
								+ "to bind to those keys, or restart this operator using a queue that does not already exist.");
			}
		}
	}

	//this function returns true if we create a queue, false if we use a queue that already exists
	private boolean initializeQueue(Connection connection) throws IOException {
		boolean createdQueue = true;
		
		if (queueName.isEmpty()) {
			queueName = channel.queueDeclare().getQueue();
		} else {
			try {
				channel.queueDeclarePassive(queueName);
				trace.log(TraceLevel.INFO, "Queue was found, therefore no queue will be declared and user queue configurations will be ignored.");
				createdQueue = false;
			} catch (IOException e) {
				channel = connection.createChannel();
				channel.queueDeclare(queueName, false, false, true, null);
				trace.log(TraceLevel.INFO, "Queue was not found, therefore non-durable, auto-delete queue will be declared.");
			}
		}
		return createdQueue;
	}

	/**
	 * Notification that initialization is complete and all input and output
	 * ports are connected and ready to receive and submit tuples.
	 * 
	 * @throws Exception
	 *             Operator failure, will cause the enclosing PE to terminate.
	 */
	@Override
	public synchronized void allPortsReady() throws Exception {
		processThread.start();
	}

	/**
	 * Submit new tuples to the output stream
	 * @throws IOException 
	 */
	private void produceTuples() throws IOException {
		Consumer consumer = new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope,
					AMQP.BasicProperties properties, byte[] body)
					throws IOException {
				StreamingOutput<OutputTuple> out = getOutput(0);
				OutputTuple tuple = out.newTuple();

				messageAH.setValue(tuple, body);
				
				if (routingKeyAH.isAvailable()) {
					tuple.setString(routingKeyAH.getName(),
							envelope.getRoutingKey());
					if (trace.isLoggable(TraceLevel.DEBUG))
						trace.log(TraceLevel.DEBUG, routingKeyAH.getName() + ":"
								+ envelope.getRoutingKey());
				} 				
				
				if (messageHeaderAH.isAvailable()){
					System.out.println("Trying to print headers...");
					Map<String, Object> msgHeader = properties.getHeaders();
					if (msgHeader != null && !msgHeader.isEmpty()){
						Map<String, String> headers = new HashMap<String,String>();
						Iterator<Entry<String,Object>> it = msgHeader.entrySet().iterator();
						while (it.hasNext()){
							Map.Entry<String, Object> pair = it.next();
							if (trace.isLoggable(TraceLevel.DEBUG))
								trace.log(TraceLevel.DEBUG, "Header: " + pair.getKey() + ":" + pair.getValue().toString());
							headers.put(pair.getKey(), pair.getValue().toString());
						}
						tuple.setMap(messageHeaderAH.getName(), headers);
					}
				}

				// Submit tuple to output stream
				try {
					out.submit(tuple);
				} catch (Exception e) {
					trace.log(TraceLevel.ERROR, "Catching submit exception" + e.getMessage());
					e.printStackTrace();
				}
			}
		};
		channel.basicConsume(queueName, true, consumer);
	}
	
	@Parameter(optional = true, description = "Routing key/keys to bind the queue to. If you are connecting to an existing queue, these bindings will be ignored.")
	public void setRoutingKey(List<String> values) {
		if(values!=null)
			routingKeys.addAll(values);
	}	
	
	@Parameter(optional = true, description = "Name of the queue. Main reason to specify is to facilitate parallel consuming. If this parameter is not specified, a queue will be created using a randomly generated name.")
	public void setQueueName(String value) {
		queueName = value;
	}
	
	@Parameter(optional = true, description = "Name of the RabbitMQ exchange to bind the queue to. If consuming from an already existing queue, this parameter is ignored. To use default RabbitMQ exchange, do not specify this parameter or use empty quotes: \\\"\\\".")
	public void setExchangeName(String value) {
		exchangeName = value;
	}

	/**
	 * Shutdown this operator, which will interrupt the thread executing the
	 * <code>produceTuples()</code> method.
	 * 
	 * @throws TimeoutException
	 * @throws IOException
	 */
	public synchronized void shutdown() throws IOException, TimeoutException {
		if (processThread != null) {
			processThread.interrupt();
			processThread = null;
		}
		OperatorContext context = getOperatorContext();
		trace.log(TraceLevel.ALL, "Operator " + context.getName()
				+ " shutting down in PE: " + context.getPE().getPEId()
				+ " in Job: " + context.getPE().getJobId());
		// Must call super.shutdown()
		super.shutdown();
	}
	
	public static final String DESC = 
			"This operator acts as a RabbitMQ consumer, pulling messages from a RabbitMQ broker. " + 
			"The broker is assumed to be already configured and running. " +
			"The outgoing stream can have three attributes: message, routing_key, and messageHeader. " +
			"The message is a required attribute. " +
			"The exchange name, queue name, and routing key can be specified using parameters. " +
			"If a specified exchange does not exist, it will be created as a non-durable exchange. " + 
			"If a queue name is specified for a queue that already exists, all binding parameters (exchangeName and routing_key) " + 
			"will be ignored. Only queues created by this operator will result in exchange/routing key bindings. " + 
			"All exchanges and queues created by this operator are non-durable and auto-delete." + 
			"This operator supports direct, fanout, and topic exchanges. It does not support header exchanges. " + 
			"\\n\\n**Behavior in a Consistent Region**" + 
			"\\nThis operator cannot participate in a consistent region."
			;
}