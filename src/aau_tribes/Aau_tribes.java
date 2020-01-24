/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package aau_tribes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.*;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;

/**
 * @author manuelegger
 */
public class Aau_tribes {
    static List<Player> players = Collections.synchronizedList(new ArrayList<>());
    static AAUMap aaumap = new AAUMap();
    static InvokeRequest req;
    static InvokeResult lambdaResult;
    static String accessKey = "";
    static String secretAccessKey = "";
    
    static BasicAWSCredentials credentials = new BasicAWSCredentials(accessKey, secretAccessKey);
    
    static AWSLambdaClientBuilder builder = AWSLambdaClientBuilder.standard()
            .withCredentials(new AWSStaticCredentialsProvider(credentials))
            .withRegion(Regions.EU_CENTRAL_1);
    
    static AWSLambda client = builder.build();

    public static class PlayerConnectionThread extends Thread {
        private Socket playerSocket;
        String playerName = "";
        String playerMessage;
        String responseMessage;
        JSONObject jsonInput;
        int currentVisitedObjectId;
        int currentVisitedObjectType;

        public PlayerConnectionThread(Socket playerSocket) {
            this.playerSocket = playerSocket;
        }

        @Override
        public void run() {
            try (PrintWriter printWriter = new PrintWriter(playerSocket.getOutputStream(), true);
                 BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(playerSocket.getInputStream()));) {

                System.out.println("New client connected");

                while ((playerMessage = bufferedReader.readLine()) != null) {
                    printWriter.print(printDebugCodes(playerMessage, playerName));
                    System.out.println(playerMessage);
                    try {
                        jsonInput = new JSONObject(playerMessage);
                    } catch (Exception e) {
                        System.out.println("Couldn't parse JSON: " + e);
                        jsonInput = new JSONObject("{\"action\": \"JSONParsingError\"}");
                    }
                    System.out.println(jsonInput);
                    if (!playerName.equals("") || jsonInput.getString("action").equals("PlayerLogin")) {
                        switch (jsonInput.getString("action")) {
                            case "PlayerLogin":
                                if (loginPlayer(jsonInput.getString("player"))) {
                                    playerName = jsonInput.getString("player");
                                    printWriter.println(jsonInput);
                                } else {
                                    printWriter.println("please enter valid name (longer than 3 characters and no spezial characters)");
                                }
                                break;
                            case "NewLocation":
                                MovementResponse movementResponse = aaumap.checkEvent(jsonInput.getDouble("latitude"), jsonInput.getDouble("longitude"));
                                if (!(movementResponse.getObjectType() == currentVisitedObjectType &&
                                        (movementResponse.getObjectType() == 0 || movementResponse.getObjectId() == currentVisitedObjectId))) {
                                    currentVisitedObjectType = movementResponse.getObjectType();
                                    currentVisitedObjectId = movementResponse.getObjectId();
                                    printWriter.println(movementResponse.getAnswerToSend());
                                }

                                break;
                            case "GatherResources":
                                printWriter.println(gatherResources(jsonInput));
                                break;
                            case "BuildCastle":
                                req = new InvokeRequest()
                                        .withFunctionName("BuildCastle")
                                        .withPayload(
                                                aaumap.addCastle(jsonInput.getString("player"), 
                                                        jsonInput.getDouble("latitude"), 
                                                        jsonInput.getDouble("longitude"))
                                        );
                                lambdaResult = client.invoke(req);
                                
                                printWriter.println(lambdaResult.toString());

                                        // printWriter.println(aaumap.addCastle(jsonInput.getString("player"), jsonInput.getDouble("latitude"), jsonInput.getDouble("longitude")));
                                break;
                            case "UpgradeCastle":
                                req = new InvokeRequest()
                                        .withFunctionName("UpgradeCastle")
                                        .withPayload(
                                            aaumap.upgradeCastle(jsonInput.optInt("castleId"))
                                        );
                                lambdaResult = client.invoke(req);
                                
                                printWriter.println(lambdaResult.toString());
                                
                                // printWriter.println(aaumap.upgradeCastle(jsonInput.optInt("castleId")));
                                break;
                            case "DeliverResources":
                                String response = deliverResources(jsonInput);
                                // Player resources
                                printWriter.println(response);
                                if(!response.equals(""))
                                {
                                    int castleId = jsonInput.optInt("castleId");
                                    for (Castle castle :
                                            aaumap.castles) {
                                        if(castle.getId() == castleId)
                                        {
                                            printWriter.println(castle.toEnterCastleJson());
                                            break;
                                        }

                                    }

                                }

                                break;

                            default:
                                System.out.println("actionType undefined");
                        }
                    } else {
                        printWriter.println("enter username");
                    }
                    printWriter.flush();
                }

            } catch (Exception e) {
                System.out.println(e);
            }
        }
    }


    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        //aaumap = new AAUMap();

        System.out.println("henlo");

        while (true) {
            try (ServerSocket serverSocket = new ServerSocket(6666)) {
                PlayerConnectionThread playerThread = new PlayerConnectionThread(serverSocket.accept());
                playerThread.start();
            } catch (Exception e) {
                System.out.println(e);
            }
        }
    }

    private static String deliverResources(JSONObject input)
    {
        String playerName = input.getString("player");
        Iterator<Player> iterator = players.iterator();
        while (iterator.hasNext()) {
            Player player = iterator.next();
            if (player.name.equals(playerName)) {
                aaumap.addRessourcesToCastle(input.getInt("castleId"),
                        player.getWood(), player.getStone(), player.getFood());
                player.clearResources();
                JSONObject newResponse = new JSONObject();
                newResponse.put("player", playerName);
                newResponse.put("action", "ResourcesOverview");
                newResponse.put("wood", player.wood);
                newResponse.put("stone", player.stone);
                newResponse.put("food", player.food);
                return newResponse.toString();
            }
        }
        return "";
    }

    private static String locationHandler(String locationResponse, String playerName) {
        if (locationResponse == null) {
            return "";
        }
        String[] locationInfo = locationResponse.split(" ");
        JSONObject newResponse = new JSONObject();

        if (locationInfo[0].equals("Resource")) {
            newResponse.put("action", "AvailableResources");
            newResponse.put("resourceId", locationInfo[2]);
            newResponse.put("resourceType", locationInfo[1]);
            newResponse.put("resourceAmount", locationInfo[3]);
        }
        if (locationInfo[0].equals("Castle")) {
            newResponse.put("owner", locationInfo[1]);
            newResponse.put("action", "CastleArrived");
            newResponse.put("castleId", locationInfo[2]);

            if (locationInfo[1].equals(playerName)) {
                Iterator<Player> iterator = players.iterator();
                while (iterator.hasNext()) {
                    Player player = iterator.next();
                    if (player.name.equals(playerName)) {
                        aaumap.addRessourcesToCastle(Integer.parseInt(locationInfo[2]),
                                player.getWood(), player.getStone(), player.getFood());
                        player.clearResources();
                    }
                }
            }
        }

        return newResponse.toString() + "\n";
    }

    private static boolean loginPlayer(String playerName) {
        Pattern p = Pattern.compile("[^a-z0-9 ]", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(playerName);

        if (playerName.toCharArray().length >= 3 && !m.find()) {
            if (players.stream().anyMatch((player) -> (player.name.equals(playerName)))) {
                return true;
            }
            players.add(new Player(playerName));
            return true;
        }
        return false;
    }

    private static String gatherResources(JSONObject input) {
        JSONObject newResponse = new JSONObject();
        if (aaumap.gatherResources(input.getInt("resourceId"), input.getInt("resourceAmount"))) {
            Iterator<Player> iterator = players.iterator();
            while (iterator.hasNext()) {
                Player player = iterator.next();
                if (player.name.equals(input.getString("player"))) {
                    player.addResource(input.getString("resourceType"), input.getInt("resourceAmount"));
                    newResponse.put("player", input.getString("player"));
                    newResponse.put("action", "ResourcesOverview");
                    newResponse.put("wood", player.wood);
                    newResponse.put("stone", player.stone);
                    newResponse.put("food", player.food);
                    return newResponse.toString();
                }
            }
        }
        return "Another Player already gathered this resources";
    }

    private static String printDebugCodes(String input, String playerName) {
        String output = "";
        if (input.equals("/printall")) {
            for (Player player : players) {
                if (playerName.equals(player.getName())) {
                    output += "Player Name: " + playerName + "\n";
                    output += "Resources: W:" + player.getWood() + ", S:" + player.getStone() + ", F:" + player.getFood() + "\n\n";
                    output += aaumap.printCastles() + "\n";
                    output += aaumap.printEvents() + "\n";
                }
            }
        } else if (input.equals("/printevents")) {
            output += aaumap.printEvents() + "\n";
        } else if (input.equals("/printplayer")) {
            for (Player player : players) {
                if (playerName.equals(player.getName())) {
                    output += "Player Name: " + playerName + "\n";
                    output += "Resources: W:" + player.getWood() + ", S:" + player.getStone() + ", F:" + player.getFood() + "\n";
                }
            }
        }
        return output;
    }
}
