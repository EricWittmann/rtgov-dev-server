/*
 * Copyright 2013 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.overlord.gadgets.server.mock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;

import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

/**
 * Mock versions of all the RTGov REST services.
 * @author eric.wittmann@redhat.com
 */
public class OverlordRTGovMockServlet extends HttpServlet {

    private static final long serialVersionUID = -6725923235877512418L;

    /**
     * Constructor.
     */
    public OverlordRTGovMockServlet() {
    }

    /**
     * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
            IOException {
        String path = req.getServletPath();
        System.out.println("Mock Request (GET): " + path);
        if ("/call/trace/instance".equals(path)) {
            resp.setContentType("application/json");
            send(resp, "calltrace.json");
        } else if ("/service/dependency/overview".equals(path)) {
            resp.setContentType("image/svg+xml");
            send(resp, "service-overview.svg");
        } else {
            super.doGet(req, resp);
        }
    }

    /**
     * @see javax.servlet.http.HttpServlet#doPost(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
            IOException {
        String path = req.getServletPath();
        System.out.println("Mock Request (POST): " + path);
        if ("/acm/query".equals(path)) {
            mockAcmQuery(req, resp);
        } else {
            super.doPost(req, resp);
        }
    }

    /**
     * @param req 
     * @param resp
     * @throws IOException
     */
    private void mockAcmQuery(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ServletInputStream inputStream = req.getInputStream();
        StringWriter writer = new StringWriter();
        IOUtils.copy(inputStream, writer);
        String postData = writer.toString();
        System.out.println("ACM Query post data: " + postData);
        
        if (postData.contains("ServiceDefinitions")) {
            resp.setContentType("application/json");
            System.out.println("Serving up the service defs.");
            send(resp, "service-defs.json");
        } else {
            resp.setContentType("application/json");
            System.out.println("Serving up the data.");
            send(resp, "acm-query.json");
        }
    }

    /**
     * @param resp
     * @param resourceName
     * @throws IOException
     */
    private void send(HttpServletResponse resp, String resourceName) throws IOException {
        InputStream is = getClass().getResourceAsStream(resourceName);
        OutputStream os = resp.getOutputStream();
        IOUtils.copy(is, os);
        os.flush();
        IOUtils.closeQuietly(is);
    }

}
