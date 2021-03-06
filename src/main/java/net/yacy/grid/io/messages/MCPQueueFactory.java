/**
 *  MCPQueueFactory
 *  Copyright 28.1.2017 by Michael Peter Christen, @0rb1t3r
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.grid.io.messages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

import net.yacy.grid.YaCyServices;
import net.yacy.grid.http.APIHandler;
import net.yacy.grid.http.APIServer;
import net.yacy.grid.http.JSONAPIHandler;
import net.yacy.grid.http.ServiceResponse;
import net.yacy.grid.mcp.Data;
import net.yacy.grid.mcp.api.info.StatusService;
import net.yacy.grid.mcp.api.messages.AvailableService;
import net.yacy.grid.mcp.api.messages.ReceiveService;
import net.yacy.grid.mcp.api.messages.SendService;

public class MCPQueueFactory implements QueueFactory<byte[]> {
    
    private GridBroker broker;
    private String server;
    private int port;
    
    public MCPQueueFactory(GridBroker broker, String server, int port) {
        this.broker = broker;
        this.server = server;
        this.port = port;
    }

    @Override
    public String getConnectionURL() {
        return "http://" + this.getHost() + ":" + ((this.hasDefaultPort() ? YaCyServices.mcp.getDefaultPort() : this.getPort()));
    }

    @Override
    public String getHost() {
        return this.server;
    }

    @Override
    public boolean hasDefaultPort() {
        return this.port == -1 || this.port == YaCyServices.mcp.getDefaultPort();
    }

    @Override
    public int getPort() {
        return hasDefaultPort() ? YaCyServices.mcp.getDefaultPort() : this.port;
    }

    @Override
    public Queue<byte[]> getQueue(String serviceQueueName) throws IOException {
        final int p = serviceQueueName.indexOf('_');
        if (p <= 0) return null;
        final JSONObject params = new JSONObject(true);
        params.put("serviceName", serviceQueueName.substring(0, p));
        params.put("queueName", serviceQueueName.substring(p + 1));
        return new Queue<byte[]>() {

            @Override
            public void checkConnection() throws IOException {
                String protocolhostportstub = MCPQueueFactory.this.getConnectionURL();
                ServiceResponse sr = APIServer.getAPI(StatusService.NAME).serviceImpl(protocolhostportstub, params);
                if (!sr.getObject().has("system")) throw new IOException("MCP does not respond properly");
                available(); // check on service level again
            }
            
            @Override
            public Queue<byte[]> send(byte[] message) throws IOException {
                params.put("message", new String(message, StandardCharsets.UTF_8));
                JSONObject response = getResponse(APIServer.getAPI(SendService.NAME));
                
                // read the broker to store the service definition of the remote queue, if exists
                if (success(response)) {
                    connectMCP(response);
                    return this;
                } else {
                    throw handleError(response);
                }
            }

            @Override
            public byte[] receive(long timeout) throws IOException {
                params.put("timeout", Long.toString(timeout));
                JSONObject response = getResponse(APIServer.getAPI(ReceiveService.NAME));
                
                // read the broker to store the service definition of the remote queue, if exists
                if (success(response)) {
                    connectMCP(response);
                    if (response.has(JSONAPIHandler.MESSAGE_KEY)) {
                        String message = response.getString(JSONAPIHandler.MESSAGE_KEY);
                        return message.getBytes(StandardCharsets.UTF_8);
                    }
                    throw new IOException("bad response from MCP: success but no message key");
                } else {
                    throw handleError(response);
                }
            }

            @Override
            public int available() throws IOException {
                JSONObject response = getResponse(APIServer.getAPI(AvailableService.NAME));
                
                // read the broker to store the service definition of the remote queue, if exists
                if (success(response)) {
                    connectMCP(response);
                    if (response.has(JSONAPIHandler.AVAILABLE_KEY)) {
                        int available = response.getInt(JSONAPIHandler.AVAILABLE_KEY);
                        return available;
                    }
                    throw new IOException("bad response from MCP: success but no message key");
                } else {
                    throw handleError(response);
                }
            }
            private JSONObject getResponse(APIHandler handler) throws IOException {
                String protocolhostportstub = MCPQueueFactory.this.getConnectionURL();
                ServiceResponse sr = handler.serviceImpl(protocolhostportstub, params);
                return sr.getObject();
            }
            private boolean success(JSONObject response) {
                return response.has(JSONAPIHandler.SUCCESS_KEY) && response.getBoolean(JSONAPIHandler.SUCCESS_KEY);
            }
            private void connectMCP(JSONObject response) {
                if (response.has(JSONAPIHandler.SERVICE_KEY)) {
                    String broker = response.getString(JSONAPIHandler.SERVICE_KEY);
                    if (MCPQueueFactory.this.broker.connectRabbitMQ(broker)) {
                        Data.logger.info("connected MCP broker at " + broker);
                    } else {
                        Data.logger.error("failed to connect MCP broker at " + broker);
                    }
                }
            }
            private IOException handleError(JSONObject response) {
                if (response.has(JSONAPIHandler.COMMENT_KEY)) {
                    return new IOException("cannot connect to MCP: " + response.getString(JSONAPIHandler.COMMENT_KEY));
                }
                return new IOException("bad response from MCP: no success and no comment key");
            }
            
        };
    }

    @Override
    public void close() {
        // this is stateless, do nothing
    }

}
