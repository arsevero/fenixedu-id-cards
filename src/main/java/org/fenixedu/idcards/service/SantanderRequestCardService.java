package org.fenixedu.idcards.service;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;

import org.apache.cxf.endpoint.Client;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.ws.addressing.WSAddressingFeature;
import org.fenixedu.academic.domain.Person;
import org.fenixedu.idcards.IdCardsConfiguration;
import org.fenixedu.idcards.domain.SantanderEntryNew;
import org.fenixedu.idcards.domain.SantanderPhotoEntry;
import org.fenixedu.idcards.utils.SantanderEntryUtils;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import pt.ist.fenixframework.Atomic;
import pt.sibscartoes.portal.wcf.IRegistersInfo;
import pt.sibscartoes.portal.wcf.dto.FormData;
import pt.sibscartoes.portal.wcf.dto.RegisterData;
import pt.sibscartoes.portal.wcf.tui.ITUIDetailService;
import pt.sibscartoes.portal.wcf.tui.dto.TUIResponseData;
import pt.sibscartoes.portal.wcf.tui.dto.TuiPhotoRegisterData;
import pt.sibscartoes.portal.wcf.tui.dto.TuiSignatureRegisterData;

public class SantanderRequestCardService {

    private static final String ACTION_NEW = "NOVO";
    private static final String ACTION_REMI = "REMI";
    private static final String ACTION_RENU = "RENU";
    private static final String ACTION_ATUA = "ATUA";
    private static final String ACTION_CANC = "CANC";

    private static Logger logger = LoggerFactory.getLogger(SantanderRequestCardService.class);

    public static List<String> getPersonAvailableActions(Person person) {
        // TODO: just a draft
        List<String> actions = new ArrayList<>();

        if (person.getCurrentSantanderEntry() == null) {
            actions.add(ACTION_NEW);

            return actions;
        }

        // TODO: Probably have to refactor this to persist the entry state
        // and check the string correctly
        String status = getRegister(person);

        DateTime expiryDate = person.getCurrentSantanderEntry().getExpiryDate();

        if (Days.daysBetween(DateTime.now().withTimeAtStartOfDay(), expiryDate.withTimeAtStartOfDay()).getDays() < 60) {
            actions.add(ACTION_RENU);
        } else if (status.equals("Expedido")) {
            actions.add(ACTION_REMI);
        }

        if (status.equals("Expedido") || status.equals("Não produzido")) {
            actions.add(ACTION_ATUA);
            actions.add(ACTION_CANC);
        }

        return actions;
    }

    public static String getRegister(Person person) {

        JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();

        factory.setServiceClass(IRegistersInfo.class);
        factory.setAddress("https://portal.sibscartoes.pt/wcf/RegistersInfo.svc");
        factory.setBindingId("http://schemas.xmlsoap.org/wsdl/soap12/");
        factory.getFeatures().add(new WSAddressingFeature());

        //Add loggers to request
        factory.getInInterceptors().add(new LoggingInInterceptor());
        factory.getOutInterceptors().add(new LoggingOutInterceptor());

        IRegistersInfo port = (IRegistersInfo) factory.create();

        /*define WSDL policy*/
        Client client = ClientProxy.getClient(port);
        HTTPConduit http = (HTTPConduit) client.getConduit();

        //Add username and password properties
        http.getAuthorization().setUserName(IdCardsConfiguration.getConfiguration().sibsWebServiceUsername());
        http.getAuthorization().setPassword(IdCardsConfiguration.getConfiguration().sibsWebServicePassword());

        final String userName = Strings.padEnd(person.getUsername(), 10, 'x');

        RegisterData statusInformation = port.getRegister(userName);

        FormData formData = port.getFormStatus(userName);

        String template = "%s | Entity: %s | IdentRegNum: %s | NDoc: %s | Status: %s | Date: %s";
        String result = String.format(template, userName, formData.getEntityCode().getValue(), formData.getIdentRegNum().getValue(),
                formData.getNDoc().getValue(), formData.getStatus().getValue(), formData.getIdentRegNum().getValue());

        logger.debug("Result: " + result);

        return formData.getStatus().getValue();
    }

    public static void createRegister(String tuiEntry, Person person) {

        logger.debug("Entry: " + tuiEntry);
        logger.debug("Entry size: " + tuiEntry.length());

        JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();

        factory.setServiceClass(ITUIDetailService.class);
        factory.setAddress("https://portal.sibscartoes.pt/tstwcfv2/services/TUIDetailService.svc");
        factory.setBindingId("http://schemas.xmlsoap.org/wsdl/soap12/");
        factory.getFeatures().add(new WSAddressingFeature());

        //Add loggers to request
        factory.getInInterceptors().add(new LoggingInInterceptor());
        factory.getOutInterceptors().add(new LoggingOutInterceptor());

        ITUIDetailService port = (ITUIDetailService) factory.create();

        /*define WSDL policy*/
        Client client = ClientProxy.getClient(port);
        HTTPConduit http = (HTTPConduit) client.getConduit();
        //Add username and password properties
        http.getAuthorization().setUserName(IdCardsConfiguration.getConfiguration().sibsWebServiceUsername());
        http.getAuthorization().setPassword(IdCardsConfiguration.getConfiguration().sibsWebServicePassword());

        TuiPhotoRegisterData photo = getOrCreateSantanderPhoto(person);

        TuiSignatureRegisterData signature = new TuiSignatureRegisterData();

        TUIResponseData tuiResponse = port.saveRegister(tuiEntry, photo, signature);

        createSantanderEntry(person, tuiEntry, tuiResponse);

        List<String> result = new ArrayList<>();

        result.add(tuiResponse.getStatus().getValue());
        result.add(tuiResponse.getStatusDescription().getValue());
        result.add(tuiResponse.getTuiResponseLine().getValue());

        logger.debug("Status: " + result.get(0));
        logger.debug("Description: " + result.get(1));
        logger.debug("Line: " + result.get(2));
    }

    @Atomic(mode = Atomic.TxMode.WRITE)
    private static void createSantanderEntry(Person person, String tuiEntry, TUIResponseData tuiResponse) {
        String tuiResponseLine = tuiResponse.getTuiResponseLine().getValue();

        boolean registerSuccessful = tuiResponseLine.substring(1 + 10 + 5, 1 + 10 + 5 + 1).equals("0");
        String errorDescription = null;

        if (!registerSuccessful) {
            errorDescription = tuiResponseLine.substring(1 + 10 + 5 + 1 + 3, 1 + 10 + 5 + 1 + 3 + 50).trim();
        }

        new SantanderEntryNew(person, tuiEntry, tuiResponseLine, registerSuccessful, errorDescription);
    }

    private static TuiPhotoRegisterData getOrCreateSantanderPhoto(Person person) {
        final QName FILE_NAME =
                new QName("http://schemas.datacontract.org/2004/07/SibsCards.Wcf.Services.DataContracts", "FileName");
        final QName FILE_EXTENSION =
                new QName("http://schemas.datacontract.org/2004/07/SibsCards.Wcf.Services.DataContracts", "Extension");
        final QName FILE_CONTENTS =
                new QName("http://schemas.datacontract.org/2004/07/SibsCards.Wcf.Services.DataContracts", "FileContents");
        final QName FILE_SIZE = new QName("http://schemas.datacontract.org/2004/07/SibsCards.Wcf.Services.DataContracts", "Size");

        final String EXTENSION = ".jpeg";

        TuiPhotoRegisterData photo = new TuiPhotoRegisterData();

        SantanderPhotoEntry photoEntry = SantanderPhotoEntry.getOrCreatePhotoEntryForPerson(person);
        byte[] photo_contents = photoEntry.getPhotoAsByteArray();

        photo.setFileContents(new JAXBElement<>(FILE_CONTENTS, byte[].class, photo_contents));
        photo.setSize(new JAXBElement<>(FILE_SIZE, String.class, Integer.toString(photo_contents.length)));
        photo.setExtension(new JAXBElement<>(FILE_EXTENSION, String.class, EXTENSION));
        photo.setFileName(new JAXBElement<>(FILE_NAME, String.class, "foto")); //TODO

        return photo;

    }
}
