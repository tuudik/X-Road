/**
 * The MIT License
 * Copyright (c) 2015 Estonian Information System Authority (RIA), Population Register Centre (VRK)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package ee.ria.xroad.proxy.serverproxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import javax.servlet.http.HttpServletRequest;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.soap.SOAPMessage;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.HttpClient;

import org.w3c.dom.Node;

import ee.ria.xroad.common.CodedException;
import ee.ria.xroad.common.conf.serverconf.ServerConf;
import ee.ria.xroad.common.conf.serverconf.ServerConfDatabaseCtx;
import ee.ria.xroad.common.conf.serverconf.dao.WsdlDAOImpl;
import ee.ria.xroad.common.conf.serverconf.model.WsdlType;
import ee.ria.xroad.common.identifier.ServiceId;
import ee.ria.xroad.common.message.JaxbUtils;
import ee.ria.xroad.common.message.SoapMessageEncoder;
import ee.ria.xroad.common.message.SoapMessageImpl;
import ee.ria.xroad.common.message.SoapParserImpl;
import ee.ria.xroad.common.message.SoapUtils;
import ee.ria.xroad.common.message.SoapUtils.SOAPCallback;
import ee.ria.xroad.common.metadata.MethodListType;
import ee.ria.xroad.common.metadata.ObjectFactory;
import ee.ria.xroad.common.opmonitoring.OpMonitoringData;
import ee.ria.xroad.common.util.MimeTypes;
import ee.ria.xroad.proxy.common.WsdlRequestData;
import ee.ria.xroad.proxy.protocol.ProxyMessage;

import static ee.ria.xroad.common.ErrorCodes.X_INVALID_REQUEST;
import static ee.ria.xroad.common.ErrorCodes.X_UNKNOWN_SERVICE;
import static ee.ria.xroad.common.metadata.MetadataRequests.*;

@Slf4j
class MetadataServiceHandlerImpl implements ServiceHandler {

    static final JAXBContext JAXB_CTX = initJaxbCtx();
    static final ObjectFactory OBJECT_FACTORY = new ObjectFactory();

    private final ByteArrayOutputStream responseOut =
            new ByteArrayOutputStream();

    private SoapMessageImpl requestMessage;
    private SoapMessageEncoder responseEncoder;

    @Override
    public boolean shouldVerifyAccess() {
        return false;
    }

    @Override
    public boolean shouldVerifySignature() {
        return true;
    }

    @Override
    public boolean shouldLogSignature() {
        return true;
    }

    @Override
    @SneakyThrows
    public boolean canHandle(ServiceId requestServiceId,
            ProxyMessage requestProxyMessage) {
        requestMessage = requestProxyMessage.getSoap();

        switch (requestServiceId.getServiceCode()) {
            case LIST_METHODS: // $FALL-THROUGH$
            case ALLOWED_METHODS: // $FALL-THROUGH$
            case GET_WSDL:
                requestMessage = (SoapMessageImpl) new SoapParserImpl().parse(
                        requestProxyMessage.getSoapContentType(),
                        requestProxyMessage.getSoapContent());
                return true;
            default:
                return false;
        }
    }

    @Override
    public void startHandling(HttpServletRequest servletRequest,
            ProxyMessage proxyRequestMessage, HttpClient opMonitorClient,
            OpMonitoringData opMonitoringData) throws Exception {
        responseEncoder = new SoapMessageEncoder(responseOut);

        // It's required that in case of metadata service (where SOAP message is
        // not forwarded) the requestOutTs must be equal with the requestInTs
        // and the responseInTs must be equal with the responseOutTs.
        opMonitoringData.setRequestOutTs(opMonitoringData.getRequestInTs());
        opMonitoringData.setAssignResponseOutTsToResponseInTs(true);

        switch (requestMessage.getService().getServiceCode()) {
            case LIST_METHODS:
                handleListMethods(requestMessage);
                return;
            case ALLOWED_METHODS:
                handleAllowedMethods(requestMessage);
                return;
            case GET_WSDL:
                handleGetWsdl(requestMessage);
                return;
            default: // do nothing
                return;
        }
    }

    @Override
    public void finishHandling() throws Exception {
        // nothing to do
    }

    @Override
    public String getResponseContentType() {
        return responseEncoder.getContentType();
    }

    @Override
    public InputStream getResponseContent() throws Exception {
        return new ByteArrayInputStream(responseOut.toByteArray());
    }

    // ------------------------------------------------------------------------

    private void handleListMethods(SoapMessageImpl request) throws Exception {
        log.trace("handleListMethods()");

        MethodListType methodList = OBJECT_FACTORY.createMethodListType();
        methodList.getService().addAll(
                ServerConf.getAllServices(
                        request.getService().getClientId()));

        SoapMessageImpl result = createMethodListResponse(request,
                OBJECT_FACTORY.createListMethodsResponse(methodList));
        responseEncoder.soap(result, new HashMap<>());
    }

    private void handleAllowedMethods(SoapMessageImpl request)
            throws Exception {
        log.trace("handleAllowedMethods()");

        MethodListType methodList = OBJECT_FACTORY.createMethodListType();
        methodList.getService().addAll(
                ServerConf.getAllowedServices(
                        request.getService().getClientId(),
                        request.getClient()));

        SoapMessageImpl result = createMethodListResponse(request,
                OBJECT_FACTORY.createAllowedMethodsResponse(methodList));
        responseEncoder.soap(result, new HashMap<>());
    }

    private void handleGetWsdl(SoapMessageImpl request) throws Exception {
        log.trace("handleGetWsdl()");

        Unmarshaller um = JaxbUtils.createUnmarshaller(WsdlRequestData.class);

        WsdlRequestData requestData = um.unmarshal(
                SoapUtils.getFirstChild(request.getSoap().getSOAPBody()),
                WsdlRequestData.class).getValue();

        if (StringUtils.isBlank(requestData.getServiceCode())) {
            throw new CodedException(X_INVALID_REQUEST,
                    "Missing serviceCode in message body");
        }

        String url = getWsdlUrl(requestData.toServiceId(
                request.getService().getClientId()));

        if (url == null) {
            throw new CodedException(X_UNKNOWN_SERVICE,
                    "Could not find wsdl URL for service %s",
                    requestData.toServiceId(
                            request.getService().getClientId()));
        }

        log.info("Downloading WSDL from URL: {}", url);

        try (InputStream in = getWsdl(url)) {
            responseEncoder.soap(SoapUtils.toResponse(request),
                    new HashMap<>());
            responseEncoder.attachment(MimeTypes.TEXT_XML, in, null);
        }
    }

    // ------------------------------------------------------------------------

    private String getWsdlUrl(ServiceId service) throws Exception {
        return ServerConfDatabaseCtx.doInTransaction(session -> {
            WsdlType wsdl = new WsdlDAOImpl().getWsdl(session, service);
            return wsdl != null ? wsdl.getUrl() : null;
        });
    }

    private static SoapMessageImpl createMethodListResponse(
            SoapMessageImpl requestMessage,
            final JAXBElement<MethodListType> methodList) throws Exception {
        SoapMessageImpl responseMessage = SoapUtils.toResponse(requestMessage,
                new SOAPCallback() {
            @Override
            public void call(SOAPMessage soap) throws Exception {
                soap.getSOAPBody().removeContents();
                marshal(methodList, soap.getSOAPBody());
            }
        });

        return responseMessage;
    }

    private static void marshal(Object object, Node out) throws Exception {
        Marshaller marshaller = JAXB_CTX.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
        marshaller.marshal(object, out);
    }

    private static JAXBContext initJaxbCtx() {
        try {
            return JAXBContext.newInstance(ObjectFactory.class);
        } catch (JAXBException e) {
            throw new RuntimeException(e);
        }
    }

    private InputStream getWsdl(String url) throws Exception {
        HttpURLConnection con =
                (HttpURLConnection) new URL(url).openConnection();
        con.setDoOutput(true);
        con.setRequestMethod("GET");

        if (con.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("Received HTTP error: "
                    + con.getResponseCode() + " - " + con.getResponseMessage());
        }

        return con.getInputStream();
    }
}