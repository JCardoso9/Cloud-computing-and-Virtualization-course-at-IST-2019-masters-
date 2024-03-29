package pt.ulisboa.tecnico.cnv.aws.autoscaler;

import pt.ulisboa.tecnico.cnv.aws.observer.AbstractManagerObservable;

import java.util.*;
import pt.ulisboa.tecnico.cnv.parser.Request;

import pt.ulisboa.tecnico.cnv.aws.observer.*;

import java.util.TimerTask;
import java.lang.Runnable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;




public class EC2InstancesManager extends AbstractManagerObservable {


	static EC2InstancesManager  instance;

	int MAXIMUM_FAILED_HEALTH_CHECKS = 3;
	int SECONDS_BETWEEN_HEALTH_CHECKS = 20;
	int FIRST_HEALTH_CHECK = 20;
	int MAXIMUM_REQUEST_COMPLEXITY = 10;


    String ec2InstanceID;
    boolean isInstanceBeingCreated;


    private HashMap<String, EC2InstanceController> ec2instances = new HashMap<String, EC2InstanceController>();
    private HashMap<String, Integer> ec2InstancesHealth = new HashMap<String, Integer>();


    private static final Comparator<EC2InstanceController> instanceComparator = new Comparator<EC2InstanceController>() {
        @Override
        public int compare(EC2InstanceController i1, EC2InstanceController i2) {
            int smallestLoad = (i1.getLoad() < i2.getLoad()) ? i1.getLoad() : i2.getLoad();
            return smallestLoad;
        }
    };

    private EC2InstancesManager() {

    	ExecutorService executor = Executors.newCachedThreadPool();
		//server.setExecutor(Executors.newCachedThreadPool());
		executor.submit(new HealthCheckCreator());
    }


    public synchronized static EC2InstancesManager getInstance() {
    	if (instance == null){ 
    		instance = new EC2InstancesManager();
    	}
    	return instance;
    }

    public int getNumberInstances() { return ec2instances.size();}


    public void addInstance(EC2InstanceController instance){
    	isInstanceBeingCreated = false;
        ec2instances.put(instance.getInstanceID(), instance);
    }

    public void removeInstance(EC2InstanceController instance){
        ec2instances.remove(instance.getInstanceID());
    }


    public List<String> getIdleInstances(){
    	List<String> idleInstances = new ArrayList<String>();
        for (EC2InstanceController instance : ec2instances.values()){
        	if (instance.getLoad() == 0) {
        		idleInstances.add(instance.getInstanceID());
        	}
        }
        return idleInstances;
    }




    public int calculateTotalClusterLoad(){
    	int totalClusterLoad = 0;
        for (EC2InstanceController instance : ec2instances.values()){
        	totalClusterLoad += instance.getLoad();
        }
        if (isInstanceBeingCreated) totalClusterLoad += 10;
        return totalClusterLoad;
    }


    public int getClusterAvailableLoad(){
    	int totalLoadPossible = MAXIMUM_REQUEST_COMPLEXITY * ec2instances.size();
    	if (isInstanceBeingCreated) totalLoadPossible += 10;
    	int availableClusterLoad = totalLoadPossible - calculateTotalClusterLoad();
    	return availableClusterLoad;
    }


    public EC2InstanceController createInstance() {
    	isInstanceBeingCreated = true;
        return EC2InstanceController.requestNewEC2Instance();

    }


    public void deleteInstance(String instanceID){
        if (ec2instances.containsKey(instanceID)){
        	EC2InstanceController instance = ec2instances.get(instanceID);
            removeInstance(instance);
            instance.shutDownEC2Instance();
        }
        else{
        	System.out.println("There was no instance with this ID");
        }
    }


    public boolean isInstanceBeingCreated(){
    	return isInstanceBeingCreated;
    }

    public int getLoadOfInstance(String instanceID){
    	return ec2instances.get(instanceID).getLoad();
    }

    public int getAvailableLoadInstance(String instanceID){
    	return MAXIMUM_REQUEST_COMPLEXITY - ec2instances.get(instanceID).getLoad();
    }


    public void markForShutdown(String instanceID){
    	ec2instances.get(instanceID).markForShutdown();
    }

    public void reActivate(String instanceID){
    	ec2instances.get(instanceID).reActivate();
    }

   //Decide the best instance given the incoming request
    public synchronized EC2InstanceController getInstanceWithSmallerLoad(Request request){
        System.out.println("Getting best request");
    	ArrayList<EC2InstanceController> instances = new ArrayList<EC2InstanceController>(ec2instances.values());
        System.out.println("SIze : " + instances.size());
        Collections.sort(instances, instanceComparator);
        List<String> idleInstances = getIdleInstances();
        if (instances.isEmpty()){
            return null;
        }
        else{
            int bestIndex = 0;
            EC2InstanceController bestInstance = instances.get(bestIndex);
            while (bestInstance.isMarkedForShutdown() && bestIndex < instances.size()){
                bestIndex++;
                if (bestIndex == instances.size()){
			//No suitable instances found
                    bestInstance = null;
                    break;
                }
                else{
                    bestInstance = instances.get(bestIndex);
                }
            }
            if (idleInstances.size() != 0){
                if (bestInstance == null || bestInstance.getLoad() > 0){
			//Idle instance is better suited to solve the request, remove shutdown mark
                    bestInstance = ec2instances.get(idleInstances.get(0));
                    if (bestInstance.isMarkedForShutdown()) EC2AutoScaler.getInstance().quitShutdownProcedure(bestInstance.getInstanceID());
                }
            }
            else if (bestInstance == null){
                return null;
            }
            if (bestInstance.getLoad() + request.getEstimatedCost() > EC2AutoScaler.MAXIMUM_REQUEST_COMPLEXITY){

                if (!isInstanceBeingCreated) {
                	System.out.println("Creating in manager new instance");
                	EC2InstanceController newInstance = createInstance();
	                System.out.println("ADDING  NEW REQUEST 1 : " + bestInstance.getInstanceID());
	                return null;
	            } 
	            else return null;
            }
            System.out.println("ADDING  NEW REQUEST 2 : " + bestInstance.getInstanceID());
            bestInstance.addNewRequest(request);

            return bestInstance;
        }
    }

    public void removeRequest(String instanceID, Request request){
        if (ec2instances.containsKey(instanceID)){
            EC2InstanceController instance = ec2instances.get(instanceID);
            instance.removeRequest(request);
        }
    }


    public synchronized void  checkInstances(){
    	for (EC2InstanceController instance : ec2instances.values()){
        	boolean healthy = instance.checkHealth();
        	System.out.println(instance.getInstanceIP() + " Healthy:  " + healthy);
        	updateHealthInstance(instance.getInstanceID(), healthy);
        }
    }

    public synchronized void  updateHealthInstance(String instanceID, boolean healthy){
    	if (!ec2InstancesHealth.containsKey(instanceID)) {
    		if (healthy) ec2InstancesHealth.put(instanceID, 0);
    		else ec2InstancesHealth.put(instanceID, 1);
   		}

    	else {
	    	int nrFailedChecks = ec2InstancesHealth.get(instanceID);

	    	if (healthy){
	    		if (nrFailedChecks > 0) ec2InstancesHealth.put(instanceID, 0);
	    	}

	    	else{
	    		if (nrFailedChecks == MAXIMUM_FAILED_HEALTH_CHECKS - 1){
	    			ec2instances.remove(instanceID);
	    			ec2InstancesHealth.remove(instanceID);
	    		}
	    		else ec2InstancesHealth.put(instanceID, nrFailedChecks + 1);
	    	}
	    }
    }

    class RunHealthCheckTimer extends TimerTask {

        public void run() {
            if (!ec2instances.isEmpty()) checkInstances();
        }
    }


    class HealthCheckCreator implements Runnable {
	
        public void run() {
            Timer timer = new Timer();
       		timer.schedule(new RunHealthCheckTimer(), SECONDS_BETWEEN_HEALTH_CHECKS * 1000, SECONDS_BETWEEN_HEALTH_CHECKS * 1000);
        }
    }

    
}
