package pt.ulisboa.tecnico.cnv.aws.balancer;


import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBScanExpression;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.ec2.AmazonEC2;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import pt.ulisboa.tecnico.cnv.parser.QueryParser;
import pt.ulisboa.tecnico.cnv.parser.Request;
import pt.ulisboa.tecnico.cnv.storage.DynamoDBStorage;
import pt.ulisboa.tecnico.cnv.storage.RequestMapping;
import pt.ulisboa.tecnico.cnv.aws.autoscaler.*;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMappingException;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.OutputStream;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import java.net.URL;
import com.sun.net.httpserver.Headers;
import java.net.HttpURLConnection;
import java.lang.Runnable;

public class LoadBalancer implements Runnable{
	private static final int balancerPort = 8000;
	private static LoadBalancer loadBalancer;
	private static DynamoDBMapper mapper;
	private static double maxMetric = 0;
	private static double largestMapArea = 0;
	private static double distanceLargestMap = 0;
	private static int n = 10;
	private static EC2InstancesManager manager;
	private static HashMap<String, ArrayList<RequestMapping>> lastMappings = new HashMap<>(); 

	public static void main(String[] args) throws Exception {

		ExecutorService executor = Executors.newFixedThreadPool(2);
		//server.setExecutor(Executors.newCachedThreadPool());
		executor.submit(EC2AutoScaler.getInstance());
		executor.submit(new LoadBalancer());
	
	}
	public void run() {
		try{
			final HttpServer server = HttpServer.create(new InetSocketAddress(balancerPort), 0);

			server.createContext("/climb", new MyHandler());
			server.setExecutor(Executors.newCachedThreadPool());
			server.start();
			System.out.println(server.getAddress().toString());
			mapper = DynamoDBStorage.getInstance().mapper;
			manager = EC2InstancesManager.getInstance();
		}catch(Exception e){
			e.printStackTrace();
		}
	}

	


	private static String GetLimitPoint(double radius, int point, boolean min, int datasetSize){
		//Calculate the interval of points allowed within the 10% radius
		if (min){
			double minimumPoint = point-radius;
			if (minimumPoint < 0){
				minimumPoint = 0;
			}
			return Double.toString(minimumPoint);
		}
		else{
			double maximumPoint = point + radius;
			if (maximumPoint > datasetSize){
				maximumPoint = datasetSize;
			}
			return Double.toString(maximumPoint);
		}
	}

	private static int GetDatasetSize(String dataset){
		//Obtain dataset size and check wether it's the largest found thus far
		String[] splitDataset = dataset.split("_");
		if (splitDataset.length >= 3){
			int size = Integer.parseInt(splitDataset[2].split("x")[0]);
			if (largestMapArea < (size*size)){
				largestMapArea = size*size;
				distanceLargestMap = Math.sqrt((Math.pow((0-size),2) + Math.pow((0-size),2)));
			}
			return size;
		}
		return 0;
	}

	static List<RequestMapping> QueryDB(Request request){
		try{
			int datasetSize = GetDatasetSize(request.getDataset());
			//10% radius.
			double intervalAllowed = datasetSize * 0.1;
			Map<String, AttributeValue> eav = new HashMap<String, AttributeValue>();
	        eav.put(":dataset", new AttributeValue().withS(request.getDataset()));
	        eav.put(":strategy", new AttributeValue().withS(request.getStrategy()));
	        eav.put(":minEntryX0" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.getX0(), true, datasetSize)));
	        eav.put(":maxEntryX0" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.getX0(), false, datasetSize)));
	        eav.put(":minEntryY0" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.getY0(), true, datasetSize)));
	        eav.put(":maxEntryY0" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.getY0(), false, datasetSize)));
	        eav.put(":minOutX1" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.getX1(), true, datasetSize)));
	        eav.put(":maxOutX1" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.getX1(), false, datasetSize)));
	        eav.put(":minOutY1" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.getY1(), true, datasetSize)));
			eav.put(":maxOutY1" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.getY1(), false, datasetSize)));
			eav.put(":minXs" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.Xs(), true, datasetSize)));
			eav.put(":maxXs" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.Xs(), false, datasetSize)));
			eav.put(":minYs" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.Ys(), true, datasetSize)));
			eav.put(":maxYs" , new AttributeValue().withN(GetLimitPoint(intervalAllowed, request.Ys(), false, datasetSize)));
			//Query the database
	        DynamoDBQueryExpression<RequestMapping> queryExpression = new DynamoDBQueryExpression<RequestMapping>()
	        		.withKeyConditionExpression("Dataset = :dataset")
	        		.withFilterExpression("Strategy = :strategy"
	                        + " and X0 between :minEntryX0 and :maxEntryX0"
	                        + " and Y0 between :minEntryY0 and :maxEntryY0"
	                        + " and X1 between :minOutX1 and :maxOutX1"
	                        + " and Y1 between :minOutY1 and :maxOutY1"
	                        + " and Xs between :minXs and :maxXs"
	                        + " and Xs between :minYs and :maxYs")
	                .withExpressionAttributeValues(eav);
	        List<RequestMapping> mapping = mapper.query(RequestMapping.class, queryExpression);
	        return mapping;
         } catch (AmazonServiceException ase) {
            System.out.println("Message:    " + ase.getMessage());
            System.out.println("Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            System.out.println("Message: " + ace.getMessage());
        }
		return Collections.emptyList();
		
	}

	static double CalculateWorstCaseDistance(int x0, int x1, int y0, int y1, int xs, int ys){
		int farthestPointX = (Math.abs(xs-x0) > Math.abs(x1-xs)) ? x0 : x1;
		int farthestPointY = (Math.abs(ys-y0) > Math.abs(y1-ys)) ? y0 : y1;
		return Math.sqrt((Math.pow((farthestPointX-xs),2) + Math.pow((farthestPointY-ys),2)));
	}

	static boolean CheckIfInCache(Request request){
		//Check wether the incoming request is present in cache already
		if (lastMappings.get(request.getDataset()) != null){
			for (RequestMapping mapping : lastMappings.get(request.getDataset())){
				System.out.println("In cache, mapping : " + mapping.toString());
				System.out.println("In cache, request : " + request.toString());
				System.out.println(mapping.getX0() == request.getX0());
				System.out.println(mapping.getY0() == request.getY0());
				System.out.println(mapping.getX1() == request.getX1());
				System.out.println(mapping.getY1() == request.getY1());
				System.out.println(mapping.getStrategy().equals(request.getStrategy()));
				System.out.println(mapping.getXs() == request.Xs());
				System.out.println(mapping.getYs() == request.Ys());
				if (mapping.getX0() == request.getX0() && mapping.getX1() == request.getX1() && mapping.getXs() == request.Xs() && mapping.getYs() == request.Ys() &&
					mapping.getY0() == request.getY0() && mapping.getY1() == request.getY1() && mapping.getStrategy().equals(request.getStrategy())){
					request.setEstimatedCost((long)Math.round((mapping.getMetrics()/maxMetric)*n));
					System.out.println("Found same request in cache, cost : " + request.getEstimatedCost());
					return true;
				}
			}
		}
		return false;
	}

	static Request EstimateRequestComplexity(Request request){
		//Check if in cache later.
		System.out.println("Estimating request complexity....");
		if(!CheckIfInCache(request)){
			List<RequestMapping> mappingList = QueryDB(request);
			double metricsAvg = -1;
			int metricsNumber = 0;
			if (mappingList.size() > 0){
				System.out.println("Found stuff in database");
				for (RequestMapping mapping : mappingList){
					//Check if database contains the exact same request
					if (mapping.getX0() == request.getX0() && mapping.getX1() == request.getX1() && mapping.getY0() == request.getY0()
						&& mapping.getY1() == request.getY1() && mapping.getXs() == request.Xs() && mapping.getYs() == request.Ys() && mapping.getStrategy().equals(request.getStrategy())){
						if (maxMetric < mapping.getMetrics()){
							maxMetric = mapping.getMetrics();
						}
						long cost = Math.round((mapping.getMetrics()/maxMetric)*n);
						request.setEstimatedCost(cost);
						System.out.println("Found exact same request : "  + request.getEstimatedCost());
						return request;
					}
					
					metricsAvg += mapping.getMetrics();
					metricsNumber++;
				}
				//Average the requests in database
				metricsAvg = metricsAvg/metricsNumber;
				long cost = Math.round((metricsAvg/maxMetric)*n);
				request.setEstimatedCost(cost);
				System.out.println("Average of similar requests cost : " + request.getEstimatedCost());
			}
			else{
				System.out.println("Nothing in db ");
				//Estimate cost.
				double mapArea = (request.getX1()-request.getX0())*(request.getY1()-request.getY0());
				double areaRatio = mapArea/largestMapArea;
				double distanceWorstCase = CalculateWorstCaseDistance(request.getX0(),request.getX1(),request.getY0(),request.getY1(),request.Xs(),request.Ys());
				double distanceRatio = distanceWorstCase/distanceLargestMap;
				//This is incorrect and can give values above n, need to rethink formula;
				long cost = Math.round(((areaRatio + distanceRatio)/2)*n);
				request.setEstimatedCost(cost);
				System.out.println("Cost : " + Long.toString(cost));
				
			}
		}
		return request;
	}

	static void SelectBestInstanceAndSendRequest(Request request, final HttpExchange t){
		EC2InstanceController bestInstance = manager.getInstanceWithSmallerLoad(request);
		try{
			// Could not retrieve an instance, wait and ask again.
			while (bestInstance == null){
				System.out.println("Could not obtain instance to send request to, waiting some seconds...");
				Thread.sleep(10000);
				bestInstance = manager.getInstanceWithSmallerLoad(request);
			}
			//Associate with the request a unique ID that is then appended to the raw query
			request.setRequestId(UUID.randomUUID().toString());
			String bestInstanceIp = bestInstance.getInstanceIP();
			URL url = new URL("http://" + bestInstanceIp + ":8000/climb?" + request.getRawQuery());
			System.out.println("http://" + bestInstanceIp + ":8000/climb?" + request.getRawQuery());
			HttpURLConnection con = (HttpURLConnection) url.openConnection();
			con.setRequestMethod("GET");
			System.out.println("Connection timeout : " + con.getConnectTimeout());
			System.out.println("Read timeout : " + con.getReadTimeout());
			System.out.println("Sent request");
			DataInputStream is = new DataInputStream((con.getInputStream()));
			//Obtain response
	  		byte[] responseBytes = new byte[con.getContentLength()];
			//Check wether response was successful.
	  		if (Math.floor(con.getResponseCode()/100) != 2){
	  			System.out.println("Error code : " + con.getResponseCode());
	  			manager.removeRequest(bestInstance.getInstanceID(), request);
	  			con.disconnect();
	  			SelectBestInstanceAndSendRequest(request, t);
	  			//Something went wrong, need to re-ask for new instance to send request
	  		}
	  		else{
				//Read response
		  		int bytes = 0;
		  		while (bytes < con.getContentLength()){
		  			bytes += is.read(responseBytes, bytes, responseBytes.length - bytes);
		  			System.out.println("Getting bytes...");
		  		}
		  		UpdateMaxMetricAndCache(request.getRequestId(), request.getDataset());
		  		System.out.println("Received response");

		  		if (con != null){
		  			System.out.println("Closing connection");
		  			con.disconnect();
		  		}
				//Disassociate request with instance.
		  		manager.removeRequest(bestInstance.getInstanceID(), request);

		  		final Headers hdrs = t.getResponseHeaders();
				t.sendResponseHeaders(200, responseBytes.length);
				hdrs.add("Content-Type", "image/png");
				hdrs.add("Access-Control-Allow-Origin", "*");
				hdrs.add("Access-Control-Allow-Credentials", "true");
				hdrs.add("Access-Control-Allow-Methods", "POST, GET, HEAD, OPTIONS");
				hdrs.add("Access-Control-Allow-Headers",
						"Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");

				//Show response to user
		  		final OutputStream os = t.getResponseBody();
		        os.write(responseBytes);
		        os.close();
	        }

		}catch(IOException e){
			System.out.println("Received IOException exception when sending request");
			//Something went wrong, need to re-ask for new instance to send request
			if (bestInstance != null){
				manager.removeRequest(bestInstance.getInstanceID(), request);
			}
			SelectBestInstanceAndSendRequest(request, t);
			
		}catch(Exception e){
			System.out.println("Received random exception when sending request");
			e.printStackTrace();
		}
	}

	static class MyHandler implements HttpHandler {
		private static AmazonEC2 ec2;
		private static ResourceBundle properties;
    	private static String REGION = "region";

		public MyHandler(){
		}
		//Handle incoming requests
		@Override
		public void handle(final HttpExchange t) throws IOException {
			final String query = t.getRequestURI().getQuery();
			Request request = new QueryParser().parseAndGetRequest(query);
			request.setRequestId(UUID.randomUUID().toString());
			request.setEstimatedCost(0);
			System.out.println("Load Balancer received request, query: " + query);
			long threadId = Thread.currentThread().getId();
			request = EstimateRequestComplexity(request);
			//Maybe store these estimated complexity values in database.
			SelectBestInstanceAndSendRequest(request,t);

		}

		private static void checkResourceBundleKeys(ResourceBundle props) throws Exception {
        // If the required properties exist and have a value
	        if (!(props.containsKey(REGION)
	            || !(!props.getString(REGION).isEmpty()))) {
	            throw new Exception("Cannot load properties from file or they have no value." +
	                    " Make sure all properties have been declared.");
	        }
		}
	}
	
	// Check whether max metric needs to be updated and insert the newly executed request in cache
	private static void UpdateMaxMetricAndCache(String requestId, String requestDataset){
		try{
			RequestMapping mapping = mapper.load(RequestMapping.class, requestDataset, requestId);

			if (mapping != null){
				if (lastMappings.get(requestDataset) == null){
					ArrayList<RequestMapping> firstMapping = new ArrayList<RequestMapping>();
					firstMapping.add(mapping);
					lastMappings.put(requestDataset, firstMapping);
				}
				else{
					ArrayList<RequestMapping> mappings = lastMappings.get(requestDataset);
					if (mappings.size() == 10){
						mappings.remove(0);
					}
					mappings.add(mapping);
					lastMappings.put(requestDataset, mappings);
				}
				if(mapping.getMetrics() > maxMetric){
					maxMetric = mapping.getMetrics();
					System.out.println("New Max Metric : " + maxMetric);
				}
			}
		}catch(DynamoDBMappingException e){
			System.out.println("Mapping : " + e.getMessage());
		}catch(Exception e){
			e.printStackTrace();
		}
	}
}
