package org.deeplearning4j.iterativereduce.actor.multilayer;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.deeplearning4j.datasets.DataSet;
import org.deeplearning4j.iterativereduce.actor.core.Ack;
import org.deeplearning4j.iterativereduce.actor.core.ClearWorker;
import org.deeplearning4j.iterativereduce.actor.core.Job;
import org.deeplearning4j.iterativereduce.actor.core.NeedsModelMessage;
import org.deeplearning4j.iterativereduce.actor.core.actor.MasterActor;
import org.deeplearning4j.iterativereduce.actor.util.ActorRefUtils;
import org.deeplearning4j.nn.BaseMultiLayerNetwork;
import org.deeplearning4j.scaleout.conf.Conf;
import org.deeplearning4j.scaleout.iterativereduce.Updateable;
import org.deeplearning4j.scaleout.iterativereduce.multi.UpdateableImpl;
import org.jblas.DoubleMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
import akka.contrib.pattern.DistributedPubSubExtension;
import akka.contrib.pattern.DistributedPubSubMediator;
import akka.contrib.pattern.DistributedPubSubMediator.Put;
import akka.dispatch.Futures;
import akka.japi.Function;


public class WorkerActor extends org.deeplearning4j.iterativereduce.actor.core.actor.WorkerActor<UpdateableImpl> {
	protected BaseMultiLayerNetwork network;
	protected DoubleMatrix combinedInput;

	protected UpdateableImpl workerUpdateable;
	protected ActorRef mediator = DistributedPubSubExtension.get(getContext().system()).mediator();
	protected Cancellable heartbeat;
	protected static Logger log = LoggerFactory.getLogger(WorkerActor.class);
	public final static String SYSTEM_NAME = "Workers";

	public WorkerActor(Conf conf) {
		super(conf);
		setup(conf);
		//subscribe to broadcasts from workers (location agnostic)
		mediator.tell(new Put(getSelf()), getSelf());

		//subscribe to broadcasts from master (location agnostic)
		mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.BROADCAST, getSelf()), getSelf());


		//subscribe to broadcasts from master (location agnostic)
		mediator.tell(new DistributedPubSubMediator.Subscribe(id, getSelf()), getSelf());

		heartbeat();

	}

	public WorkerActor(ActorRef clusterClient,Conf conf) {
		super(conf,clusterClient);
		setup(conf);
		//subscribe to broadcasts from workers (location agnostic)
		mediator.tell(new Put(getSelf()), getSelf());

		//subscribe to broadcasts from master (location agnostic)
		mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.BROADCAST, getSelf()), getSelf());


		//subscribe to broadcasts from master (location agnostic)
		mediator.tell(new DistributedPubSubMediator.Subscribe(id, getSelf()), getSelf());

		heartbeat();

	}





	public static Props propsFor(ActorRef actor,Conf conf) {
		return Props.create(WorkerActor.class,actor,conf);
	}

	public static Props propsFor(Conf conf) {
		return Props.create(WorkerActor.class,conf);
	}

	protected void confirmWorking() {
		//reply
		mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
				getCurrent()), getSelf());	
	}

	protected void finishedWork() {
		getCurrent().setDone(true);
		log.info("Finished work " + id);
		//reply
		mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
				getCurrent()), getSelf());	
		clearCurrentJob();
	}


	protected void heartbeat() {
		heartbeat = context().system().scheduler().schedule(Duration.apply(10, TimeUnit.SECONDS), Duration.apply(10, TimeUnit.SECONDS), new Runnable() {

			@Override
			public void run() {
				log.info("Sending heartbeat to master");
				mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
						id), getSelf());	

			}

		}, context().dispatcher());

	}

	@SuppressWarnings("unchecked")
	@Override
	public void onReceive(Object message) throws Exception {
		if (message instanceof DistributedPubSubMediator.SubscribeAck) {
			DistributedPubSubMediator.SubscribeAck ack = (DistributedPubSubMediator.SubscribeAck) message;
			log.info("Subscribed to " + ack.toString());
		}

		else if(message instanceof Job) {
			Job j = (Job) message;
			log.info("Confirmation from " + j.getWorkerId() + " on work");
			setCurrent(j);
			List<DataSet> input = (List<DataSet>) j.getWork();
			confirmWorking();
			updateTraining(input);


		}

		else if(message instanceof BaseMultiLayerNetwork) {
			setNetwork((BaseMultiLayerNetwork) message);
			log.info("Set network");
		}

		else if(message instanceof Ack) {
			log.info("Ack from master on worker " + id);
		}

		else if(message instanceof Updateable) {
			UpdateableImpl m = (UpdateableImpl) message;
			setWorkerUpdateable(m);
			log.info("Updated worker network");
			if(m.get() == null) {
				log.warn("Unable to initialize network; network was null");
				throw new IllegalArgumentException("Network was null");
			}

			setNetwork(m.get().clone());
		}
		else
			unhandled(message);
	}

	protected  void updateTraining(List<DataSet> list) {
		DoubleMatrix newInput = new DoubleMatrix(list.size(),list.get(0).getFirst().columns);
		DoubleMatrix newOutput = new DoubleMatrix(list.size(),list.get(0).getSecond().columns);
		for(int i = 0; i < list.size(); i++) {
			newInput.putRow(i,list.get(i).getFirst());
			newOutput.putRow(i,list.get(i).getSecond());
		}

		setCombinedInput(newInput);
		setOutcomes(newOutput);

		Future<UpdateableImpl> f = Futures.future(new Callable<UpdateableImpl>() {

			@Override
			public UpdateableImpl call() throws Exception {

				UpdateableImpl work = compute();
				log.info("Updating parent actor...");
				//update parameters in master param server
				mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
						work), getSelf());	
				return work;
			}

		}, getContext().dispatcher());

		ActorRefUtils.throwExceptionIfExists(f, context().dispatcher());
	}

	@Override
	public  UpdateableImpl compute(List<UpdateableImpl> records) {
		return compute();
	}

	@Override
	public  synchronized UpdateableImpl compute() {
		log.info("Training network");
		while(getNetwork() == null) {
			log.info("Network is null, this worker has recently joined the cluster. Asking master for a copy of the current network");
			mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
					new NeedsModelMessage(id)), getSelf());	
			try {
				Thread.sleep(15000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		getNetwork().trainNetwork(this.getCombinedInput(),this.getOutcomes(),extraParams);
		finishedWork();
		return new UpdateableImpl(getNetwork());
	}

	@Override
	public boolean incrementIteration() {
		return false;
	}

	@Override
	public void setup(Conf conf) {
		super.setup(conf);
	}


	@Override
	public SupervisorStrategy supervisorStrategy() {
		return new OneForOneStrategy(0, Duration.Zero(),
				new Function<Throwable, Directive>() {
			public Directive apply(Throwable cause) {
				log.error("Problem with processing",cause);
				return SupervisorStrategy.stop();
			}
		});
	}


	@Override
	public void aroundPostStop() {
		super.aroundPostStop();
		//replicate the network
		mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
				new ClearWorker(id)), getSelf());
		heartbeat.cancel();
	}



	@Override
	public  UpdateableImpl getResults() {
		return workerUpdateable;
	}

	@Override
	public synchronized void update(UpdateableImpl t) {
		this.workerUpdateable = t;
	}


	public synchronized BaseMultiLayerNetwork getNetwork() {
		return network;
	}


	public synchronized void setNetwork(BaseMultiLayerNetwork network) {
		this.network = network;
	}


	public synchronized DoubleMatrix getCombinedInput() {
		return combinedInput;
	}


	public synchronized void setCombinedInput(DoubleMatrix combinedInput) {
		this.combinedInput = combinedInput;
	}





	public synchronized UpdateableImpl getWorkerUpdateable() {
		return workerUpdateable;
	}


	public synchronized void setWorkerUpdateable(UpdateableImpl workerUpdateable) {
		this.workerUpdateable = workerUpdateable;
	}






}