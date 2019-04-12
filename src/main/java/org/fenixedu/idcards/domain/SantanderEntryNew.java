package org.fenixedu.idcards.domain;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.fenixedu.bennu.core.domain.Bennu;
import org.fenixedu.bennu.core.domain.User;
import org.fenixedu.santandersdk.dto.GetRegisterResponse;
import org.fenixedu.santandersdk.service.SantanderEntryValidator;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.format.DateTimeFormat;

import com.google.common.base.Strings;
import com.google.gson.JsonObject;

import pt.ist.fenixframework.Atomic;

public class SantanderEntryNew extends SantanderEntryNew_Base {

    static public Comparator<SantanderEntryNew> COMPARATOR_BY_CREATED_DATE = (p1, p2) -> {
        DateTime date1 = p1.getLastUpdate();
        DateTime date2 = p2.getLastUpdate();
        return date1.compareTo(date2);
    };

    static public Comparator<SantanderEntryNew> REVERSE_COMPARATOR_BY_CREATED_DATE = COMPARATOR_BY_CREATED_DATE.reversed();

    public SantanderEntryNew(SantanderUser santanderUser) {
        User user = santanderUser.getUser();
        setRootDomainObject(Bennu.getInstance());
        SantanderEntryNew currentEntry = user.getCurrentSantanderEntryNew();
        if (currentEntry != null) {
            setPrevious(currentEntry);
            currentEntry.setNext(this);
        }
        setUser(user);
        setBase64Photo(santanderUser.getPhoto());
        setLastUpdate(DateTime.now());

        // No response from server yet
        setState(SantanderCardState.PENDING);
        setErrorDescription("");
    }

    @Atomic(mode = Atomic.TxMode.WRITE)
    public void update(SantanderUser user, String requestLine) {
        setLastUpdate(DateTime.now());
        setRequestLine(requestLine);
        setBase64Photo(user.getPhoto());
    }

    @Atomic(mode = Atomic.TxMode.WRITE)
    public void update(GetRegisterResponse registerData) {
        //TODO add more info to the card;
        SantanderCardInfo cardInfo = new SantanderCardInfo();
        cardInfo.setMifareNumber(registerData.getMifare());
        setSantanderCardInfo(cardInfo);

        setState(SantanderCardState.ISSUED);
        setLastUpdate(DateTime.now());
    }

    @Atomic(mode = Atomic.TxMode.WRITE)
    public void reset(SantanderUser user) {
        setLastUpdate(DateTime.now());
        setState(SantanderCardState.PENDING);
        setRequestLine(null);
        setBase64Photo(user.getPhoto());
    }

    @Atomic(mode = Atomic.TxMode.WRITE)
    public void saveSuccessful(String requestLine, String responseLine) {
        setRequestLine(requestLine);
        setLastUpdate(DateTime.now());
        setResponseLine(responseLine);
        setState(SantanderCardState.NEW);
    }

    @Atomic(mode = Atomic.TxMode.WRITE)
    public void saveWithError(String requestLine, String errorDescription, SantanderCardState state) {
        setRequestLine(requestLine);
        setLastUpdate(DateTime.now());
        setErrorDescription(errorDescription);
        setState(state);
    }

    @Atomic(mode = Atomic.TxMode.WRITE)
    public void updateState(SantanderCardState state) {
        setLastUpdate(DateTime.now());
        setState(state);
    }

    public static List<SantanderEntryNew> getSantanderEntryHistory(User user) {
        LinkedList<SantanderEntryNew> history = new LinkedList<>();

        for(SantanderEntryNew entry = user.getCurrentSantanderEntryNew(); entry != null; entry = entry.getPrevious()) {
            history.addFirst(entry);
        }

        return history;
    }

    public static List<SantanderEntryNew> getSantanderCardHistory(User user) {
        return getSantanderEntryHistory(user).stream()
                .filter(SantanderEntryNew::wasRegisterSuccessful)
                .sorted(REVERSE_COMPARATOR_BY_CREATED_DATE)
                .collect(Collectors.toList());
    }

    public String getIdentificationNumber() {
        return new SantanderEntryValidator().getValue(getRequestLine(), 1);
    }

    public String getName() {
        SantanderEntryValidator validator = new SantanderEntryValidator();
        String firstName = validator.getValue(getRequestLine(), 2);
        String surname = validator.getValue(getRequestLine(), 3);
        return firstName.trim() + " " + surname.trim();
    }

    public boolean wasRegisterSuccessful() {
        return getState() != SantanderCardState.IGNORED && getState() != SantanderCardState.PENDING;
    }

    public String getErrorCode() {
        if (wasRegisterSuccessful()) {
            return "";
        }

        try {
            return getResponseLine().substring(18, 20);
        } catch (StringIndexOutOfBoundsException soobe) {
            return "-1";
        }
    }

    @Override
    public String getErrorDescription() {
        if (wasRegisterSuccessful()) {
            return "";
        } else {
            return super.getErrorDescription();
        }

    }

    public String getErrorDescriptionMessage() {
        if (wasRegisterSuccessful() || Strings.isNullOrEmpty(getErrorDescription())) {
            return "";
        }

        if (getErrorCode() == null) {
            return getErrorDescription();
        }

        return getErrorCode() + " - " + getErrorDescription();
    }

    public DateTime getExpiryDate() {
        String requestLine = getRequestLine();

        SantanderEntryValidator validator = new SantanderEntryValidator();
        String expiryMonth = validator.getValue(requestLine, 18).substring(2);
        String expiryDateYear = validator.getValue(requestLine, 11).substring(5);
        String expiryDateString = expiryMonth + expiryDateYear;

        DateTime expiryDate = DateTime.parse(expiryDateString, DateTimeFormat.forPattern("MMyyyy"));

        if (expiryDate.getDayOfMonth() == 1) {
            expiryDate = expiryDate.plusMonths(1).minusDays(1);
        }

        return expiryDate;
    }
    
    public boolean canRegisterNew() {
        SantanderCardState state = getState();
        return state == SantanderCardState.IGNORED && getPrevious() == null;
    }
    
    
    public boolean canReemitCard() {
        SantanderCardState state = getState();
        SantanderEntryNew previous = getPrevious();
        return state == SantanderCardState.ISSUED
                || (previous != null && previous.getState() == SantanderCardState.ISSUED && state == SantanderCardState.IGNORED);
    }

    public boolean canRenovateCard() {
        return canReemitCard()
                && Days.daysBetween(DateTime.now().withTimeAtStartOfDay(), getExpiryDate().withTimeAtStartOfDay()).getDays() < 60;
    }

    public JsonObject getResponseAsJson() {
        //TODO
        JsonObject response = new JsonObject();

        if (getResponseLine() == null) {
            response.addProperty("status", "");
            response.addProperty("errorCode", "");
            response.addProperty("errorDescription", "Didn't get a response from the server");
        } else if (!wasRegisterSuccessful()) {
            response.addProperty("status", "Error");
            response.addProperty("errorCode", getErrorCode());
            response.addProperty("errorDescription", getErrorDescription());
        } else {
            response.addProperty("status", "Ok");
            response.addProperty("errorCode", "");
            response.addProperty("errorDescription", "");
        }
        return response;
    }

    public JsonObject getRequestAsJson() {
        return new SantanderEntryValidator().getRequestAsJson(getRequestLine());
    }
}
