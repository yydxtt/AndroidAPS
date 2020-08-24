package info.nightscout.androidaps.plugins.pump.omnipod.manager;

import android.content.Context;
import android.content.Intent;

import org.joda.time.DateTime;
import org.joda.time.Duration;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.android.HasAndroidInjector;
import info.nightscout.androidaps.activities.ErrorHelperActivity;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.db.OmnipodHistoryRecord;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.events.Event;
import info.nightscout.androidaps.interfaces.ActivePluginProvider;
import info.nightscout.androidaps.interfaces.DatabaseHelperInterface;
import info.nightscout.androidaps.interfaces.TreatmentsInterface;
import info.nightscout.androidaps.logging.AAPSLogger;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.bus.RxBusWrapper;
import info.nightscout.androidaps.plugins.general.overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.events.EventOverviewBolusProgress;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.pump.common.data.TempBasalPair;
import info.nightscout.androidaps.plugins.pump.common.defs.PumpType;
import info.nightscout.androidaps.plugins.pump.common.utils.ByteUtil;
import info.nightscout.androidaps.plugins.pump.omnipod.R;
import info.nightscout.androidaps.plugins.pump.omnipod.data.ActiveBolus;
import info.nightscout.androidaps.plugins.pump.omnipod.definition.OmnipodStorageKeys;
import info.nightscout.androidaps.plugins.pump.omnipod.definition.PodHistoryEntryType;
import info.nightscout.androidaps.plugins.pump.omnipod.definition.PodInitActionType;
import info.nightscout.androidaps.plugins.pump.omnipod.definition.PodInitReceiver;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.communication.message.response.StatusResponse;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.communication.message.response.podinfo.PodInfoRecentPulseLog;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.communication.message.response.podinfo.PodInfoResponse;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.definition.FaultEventCode;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.definition.PodInfoType;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.definition.schedule.BasalSchedule;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.definition.schedule.BasalScheduleEntry;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.ActionInitializationException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.CommandInitializationException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.CommunicationException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.CrcMismatchException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.IllegalDeliveryStatusException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.IllegalMessageAddressException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.IllegalMessageSequenceNumberException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.IllegalPacketTypeException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.IllegalPodProgressException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.IllegalResponseException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.IllegalVersionResponseTypeException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.MessageDecodingException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.NonceOutOfSyncException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.NonceResyncException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.NotEnoughDataException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.OmnipodException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.PodFaultException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.exception.PodReturnedErrorResponseException;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.manager.OmnipodManager;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.manager.PodStateManager;
import info.nightscout.androidaps.plugins.pump.omnipod.driver.manager.SetupActionResult;
import info.nightscout.androidaps.plugins.pump.omnipod.event.EventOmnipodPumpValuesChanged;
import info.nightscout.androidaps.plugins.pump.omnipod.rileylink.OmnipodRileyLinkCommunicationManager;
import info.nightscout.androidaps.plugins.pump.omnipod.util.AapsOmnipodUtil;
import info.nightscout.androidaps.utils.resources.ResourceHelper;
import info.nightscout.androidaps.utils.sharedPreferences.SP;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.SingleSubject;

@Singleton
public class AapsOmnipodManager {

    private final PodStateManager podStateManager;
    private final AapsOmnipodUtil aapsOmnipodUtil;
    private final AAPSLogger aapsLogger;
    private final RxBusWrapper rxBus;
    private final ResourceHelper resourceHelper;
    private final HasAndroidInjector injector;
    private final ActivePluginProvider activePlugin;
    private final Context context;
    private final SP sp;
    private final OmnipodManager delegate;
    private final DatabaseHelperInterface databaseHelper;

    private boolean basalBeepsEnabled;
    private boolean bolusBeepsEnabled;
    private boolean smbBeepsEnabled;
    private boolean tbrBeepsEnabled;
    private boolean podDebuggingOptionsEnabled;
    private boolean timeChangeEventEnabled;

    @Inject
    public AapsOmnipodManager(OmnipodRileyLinkCommunicationManager communicationService,
                              PodStateManager podStateManager,
                              AapsOmnipodUtil aapsOmnipodUtil,
                              AAPSLogger aapsLogger,
                              RxBusWrapper rxBus,
                              SP sp,
                              ResourceHelper resourceHelper,
                              HasAndroidInjector injector,
                              ActivePluginProvider activePlugin,
                              Context context,
                              DatabaseHelperInterface databaseHelper) {
        if (podStateManager == null) {
            throw new IllegalArgumentException("Pod state manager can not be null");
        }
        this.podStateManager = podStateManager;
        this.aapsOmnipodUtil = aapsOmnipodUtil;
        this.aapsLogger = aapsLogger;
        this.rxBus = rxBus;
        this.resourceHelper = resourceHelper;
        this.injector = injector;
        this.activePlugin = activePlugin;
        this.context = context;
        this.databaseHelper = databaseHelper;
        this.sp = sp;

        delegate = new OmnipodManager(aapsLogger, sp, communicationService, podStateManager);
    }

    @Inject
    void onInit() {
        // this cannot be done in the constructor, as sp is not populated at that time
        reloadSettings();
    }

    public void reloadSettings() {
        basalBeepsEnabled = sp.getBoolean(OmnipodStorageKeys.Prefs.BeepBasalEnabled, true);
        bolusBeepsEnabled = sp.getBoolean(OmnipodStorageKeys.Prefs.BeepBolusEnabled, true);
        smbBeepsEnabled = sp.getBoolean(OmnipodStorageKeys.Prefs.BeepSMBEnabled, true);
        tbrBeepsEnabled = sp.getBoolean(OmnipodStorageKeys.Prefs.BeepTBREnabled, true);
        podDebuggingOptionsEnabled = sp.getBoolean(OmnipodStorageKeys.Prefs.PodDebuggingOptionsEnabled, false);
        timeChangeEventEnabled = sp.getBoolean(OmnipodStorageKeys.Prefs.TimeChangeEventEnabled, true);
    }

    public PumpEnactResult pairAndPrime(PodInitActionType podInitActionType, PodInitReceiver podInitReceiver) {
        if (podInitActionType != PodInitActionType.PairAndPrimeWizardStep) {
            return new PumpEnactResult(injector).success(false).enacted(false).comment(getStringResource(R.string.omnipod_error_illegal_init_action_type, podInitActionType.name()));
        }

        long time = System.currentTimeMillis();

        try {
            Disposable disposable = delegate.pairAndPrime().subscribe(res -> //
                    handleSetupActionResult(podInitActionType, podInitReceiver, res, time, null));

            return new PumpEnactResult(injector).success(true).enacted(true);
        } catch (Exception ex) {
            String comment = handleAndTranslateException(ex);
            podInitReceiver.returnInitTaskStatus(podInitActionType, false, comment);
            addFailureToHistory(time, PodHistoryEntryType.PairAndPrime, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }
    }

    public PumpEnactResult setInitialBasalScheduleAndInsertCannula(PodInitActionType podInitActionType, PodInitReceiver podInitReceiver, Profile profile) {
        if (podInitActionType != PodInitActionType.FillCannulaSetBasalProfileWizardStep) {
            return new PumpEnactResult(injector).success(false).enacted(false).comment(getStringResource(R.string.omnipod_error_illegal_init_action_type, podInitActionType.name()));
        }

        long time = System.currentTimeMillis();

        try {
            BasalSchedule basalSchedule;
            try {
                basalSchedule = mapProfileToBasalSchedule(profile);
            } catch (Exception ex) {
                throw new CommandInitializationException("Basal profile mapping failed", ex);
            }
            Disposable disposable = delegate.insertCannula(basalSchedule).subscribe(res -> //
                    handleSetupActionResult(podInitActionType, podInitReceiver, res, time, profile));

            rxBus.send(new EventDismissNotification(Notification.OMNIPOD_POD_NOT_ATTACHED));

            return new PumpEnactResult(injector).success(true).enacted(true);
        } catch (Exception ex) {
            String comment = handleAndTranslateException(ex);
            podInitReceiver.returnInitTaskStatus(podInitActionType, false, comment);
            addFailureToHistory(time, PodHistoryEntryType.FillCannulaSetBasalProfile, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }
    }

    public PumpEnactResult getPodStatus() {
        long time = System.currentTimeMillis();
        try {
            StatusResponse statusResponse = delegate.getPodStatus();
            addSuccessToHistory(time, PodHistoryEntryType.GetPodStatus, statusResponse);
            return new PumpEnactResult(injector).success(true).enacted(false);
        } catch (Exception ex) {
            String comment = handleAndTranslateException(ex);
            addFailureToHistory(time, PodHistoryEntryType.GetPodStatus, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }
    }

    public PumpEnactResult deactivatePod(PodInitReceiver podInitReceiver) {
        long time = System.currentTimeMillis();
        try {
            delegate.deactivatePod();
        } catch (Exception ex) {
            String comment = handleAndTranslateException(ex);
            podInitReceiver.returnInitTaskStatus(PodInitActionType.DeactivatePodWizardStep, false, comment);
            addFailureToHistory(time, PodHistoryEntryType.DeactivatePod, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }

        reportImplicitlyCanceledTbr();

        addSuccessToHistory(time, PodHistoryEntryType.DeactivatePod, null);

        podInitReceiver.returnInitTaskStatus(PodInitActionType.DeactivatePodWizardStep, true, null);

        return new PumpEnactResult(injector).success(true).enacted(true);
    }

    public PumpEnactResult setBasalProfile(Profile profile) {
        long time = System.currentTimeMillis();
        try {
            BasalSchedule basalSchedule;
            try {
                basalSchedule = mapProfileToBasalSchedule(profile);
            } catch (Exception ex) {
                throw new CommandInitializationException("Basal profile mapping failed", ex);
            }
            delegate.setBasalSchedule(basalSchedule, isBasalBeepsEnabled());
            // Because setting a basal profile actually suspends and then resumes delivery, TBR is implicitly cancelled
            reportImplicitlyCanceledTbr();
            addSuccessToHistory(time, PodHistoryEntryType.SetBasalSchedule, profile.getBasalValues());
        } catch (Exception ex) {
            if ((ex instanceof OmnipodException) && !((OmnipodException) ex).isCertainFailure()) {
                reportImplicitlyCanceledTbr();
                addToHistory(time, PodHistoryEntryType.SetBasalSchedule, "Uncertain failure", false);
                return new PumpEnactResult(injector).success(false).enacted(false).comment(getStringResource(R.string.omnipod_error_set_basal_failed_uncertain));
            }
            String comment = handleAndTranslateException(ex);
            reportImplicitlyCanceledTbr();
            addFailureToHistory(time, PodHistoryEntryType.SetBasalSchedule, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }

        return new PumpEnactResult(injector).success(true).enacted(true);
    }

    public PumpEnactResult resetPodStatus() {
        podStateManager.removeState();

        reportImplicitlyCanceledTbr();

        addSuccessToHistory(System.currentTimeMillis(), PodHistoryEntryType.ResetPodState, null);

        return new PumpEnactResult(injector).success(true).enacted(true);
    }

    public PumpEnactResult bolus(DetailedBolusInfo detailedBolusInfo) {
        OmnipodManager.BolusCommandResult bolusCommandResult;

        boolean beepsEnabled = detailedBolusInfo.isSMB ? isSmbBeepsEnabled() : isBolusBeepsEnabled();

        Date bolusStarted;
        try {
            bolusCommandResult = delegate.bolus(PumpType.Insulet_Omnipod.determineCorrectBolusSize(detailedBolusInfo.insulin), beepsEnabled, beepsEnabled, detailedBolusInfo.isSMB ? null :
                    (estimatedUnitsDelivered, percentage) -> {
                        EventOverviewBolusProgress progressUpdateEvent = EventOverviewBolusProgress.INSTANCE;
                        progressUpdateEvent.setStatus(getStringResource(R.string.bolusdelivering, detailedBolusInfo.insulin));
                        progressUpdateEvent.setPercent(percentage);
                        sendEvent(progressUpdateEvent);
                    });

            bolusStarted = new Date();
        } catch (Exception ex) {
            String comment = handleAndTranslateException(ex);
            addFailureToHistory(System.currentTimeMillis(), PodHistoryEntryType.SetBolus, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }

        if (OmnipodManager.CommandDeliveryStatus.UNCERTAIN_FAILURE.equals(bolusCommandResult.getCommandDeliveryStatus())) {
            // For safety reasons, we treat this as a bolus that has successfully been delivered, in order to prevent insulin overdose

            showErrorDialog(getStringResource(R.string.omnipod_bolus_failed_uncertain), R.raw.boluserror);
        }

        detailedBolusInfo.date = bolusStarted.getTime();
        detailedBolusInfo.source = Source.PUMP;

        // Store the current bolus for in case the app crashes, gets killed, the phone dies or whatever before the bolus finishes
        // If we have a stored value for the current bolus on startup, we'll create a Treatment for it
        // However this can potentially be hours later if for example your phone died and you can't charge it
        // FIXME !!!
        // The proper solution here would be to create a treatment right after the bolus started,
        // and update that treatment after the bolus has finished in case the actual units delivered don't match the requested bolus units
        // That way, the bolus would immediately be sent to NS so in case the phone dies you could still see the bolus
        // Unfortunately this doesn't work because
        //  a) when cancelling a bolus within a few seconds of starting it, after updating the Treatment,
        //     we get a new treatment event from NS containing the originally created treatment with the original insulin amount.
        //     This event is processed in TreatmentService.createTreatmentFromJsonIfNotExists().
        //     Opposed to what the name of this method suggests, it does createOrUpdate,
        //     overwriting the insulin delivered with the original value.
        //     So practically it seems impossible to update a Treatment when using NS
        //  b) we only send newly created treatments to NS, so the insulin amount in NS would never be updated
        //
        // I discussed this with the AAPS team but nobody seems to care so we're stuck with this ugly workaround for now
        try {
            ActiveBolus activeBolus = ActiveBolus.fromDetailedBolusInfo(detailedBolusInfo);
            sp.putString(OmnipodStorageKeys.Prefs.ActiveBolus, aapsOmnipodUtil.getGsonInstance().toJson(activeBolus));
            aapsLogger.debug(LTag.PUMP, "Stored active bolus to SP for recovery");
        } catch (Exception ex) {
            aapsLogger.error(LTag.PUMP, "Failed to store active bolus to SP", ex);
        }

        // Bolus is already updated in Pod state. If this was an SMB, it could be that
        // the user is looking at the Pod tab right now, so send an extra event
        // (this is normally done in OmnipodPumpPlugin)
        sendEvent(new EventOmnipodPumpValuesChanged());

        // Wait for the bolus to finish
        OmnipodManager.BolusDeliveryResult bolusDeliveryResult =
                bolusCommandResult.getDeliveryResultSubject().blockingGet();

        detailedBolusInfo.insulin = bolusDeliveryResult.getUnitsDelivered();

        addBolusToHistory(detailedBolusInfo);

        sp.remove(OmnipodStorageKeys.Prefs.ActiveBolus);

        return new PumpEnactResult(injector).success(true).enacted(true).bolusDelivered(detailedBolusInfo.insulin);
    }

    public PumpEnactResult cancelBolus() {
        SingleSubject<Boolean> bolusCommandExecutionSubject = delegate.getBolusCommandExecutionSubject();
        if (bolusCommandExecutionSubject != null) {
            // Wait until the bolus command has actually been executed before sending the cancel bolus command
            aapsLogger.debug(LTag.PUMP, "Cancel bolus was requested, but the bolus command is still being executed. Awaiting bolus command execution");
            boolean bolusCommandSuccessfullyExecuted = bolusCommandExecutionSubject.blockingGet();
            if (bolusCommandSuccessfullyExecuted) {
                aapsLogger.debug(LTag.PUMP, "Bolus command successfully executed. Proceeding bolus cancellation");
            } else {
                aapsLogger.debug(LTag.PUMP, "Not cancelling bolus: bolus command failed");
                String comment = getStringResource(R.string.omnipod_bolus_did_not_succeed);
                addFailureToHistory(System.currentTimeMillis(), PodHistoryEntryType.CancelBolus, comment);
                return new PumpEnactResult(injector).success(true).enacted(false).comment(comment);
            }
        }

        String comment = null;
        for (int i = 1; delegate.hasActiveBolus(); i++) {
            aapsLogger.debug(LTag.PUMP, "Attempting to cancel bolus (#{})", i);
            try {
                delegate.cancelBolus(isBolusBeepsEnabled());
                aapsLogger.debug(LTag.PUMP, "Successfully cancelled bolus", i);
                addSuccessToHistory(System.currentTimeMillis(), PodHistoryEntryType.CancelBolus, null);
                return new PumpEnactResult(injector).success(true).enacted(true);
            } catch (PodFaultException ex) {
                aapsLogger.debug(LTag.PUMP, "Successfully cancelled bolus (implicitly because of a Pod Fault)");
                showPodFaultErrorDialog(ex.getFaultEvent().getFaultEventCode(), null);
                addSuccessToHistory(System.currentTimeMillis(), PodHistoryEntryType.CancelBolus, null);
                return new PumpEnactResult(injector).success(true).enacted(true);
            } catch (Exception ex) {
                aapsLogger.debug(LTag.PUMP, "Failed to cancel bolus", ex);
                comment = handleAndTranslateException(ex);
            }
        }

        addFailureToHistory(System.currentTimeMillis(), PodHistoryEntryType.CancelBolus, comment);
        return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
    }

    public PumpEnactResult setTemporaryBasal(TempBasalPair tempBasalPair) {
        boolean beepsEnabled = isTbrBeepsEnabled();
        long time = System.currentTimeMillis();
        try {
            delegate.setTemporaryBasal(PumpType.Insulet_Omnipod.determineCorrectBasalSize(tempBasalPair.getInsulinRate()), Duration.standardMinutes(tempBasalPair.getDurationMinutes()), beepsEnabled, beepsEnabled);
            time = System.currentTimeMillis();
        } catch (Exception ex) {
            if ((ex instanceof OmnipodException) && !((OmnipodException) ex).isCertainFailure()) {
                addToHistory(time, PodHistoryEntryType.SetTemporaryBasal, "Uncertain failure", false);
                return new PumpEnactResult(injector).success(false).enacted(false).comment(getStringResource(R.string.omnipod_error_set_temp_basal_failed_uncertain));
            }
            String comment = handleAndTranslateException(ex);
            addFailureToHistory(time, PodHistoryEntryType.SetTemporaryBasal, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }

        reportImplicitlyCanceledTbr();

        long pumpId = addSuccessToHistory(time, PodHistoryEntryType.SetTemporaryBasal, tempBasalPair);

        TemporaryBasal tempStart = new TemporaryBasal(injector) //
                .date(time) //
                .duration(tempBasalPair.getDurationMinutes()) //
                .absolute(tempBasalPair.getInsulinRate()) //
                .pumpId(pumpId)
                .source(Source.PUMP);

        activePlugin.getActiveTreatments().addToHistoryTempBasal(tempStart);

        return new PumpEnactResult(injector).success(true).enacted(true);
    }

    public PumpEnactResult cancelTemporaryBasal() {
        long time = System.currentTimeMillis();
        try {
            delegate.cancelTemporaryBasal(isTbrBeepsEnabled());
            addSuccessToHistory(time, PodHistoryEntryType.CancelTemporaryBasalForce, null);
        } catch (Exception ex) {
            String comment = handleAndTranslateException(ex);
            addFailureToHistory(time, PodHistoryEntryType.CancelTemporaryBasalForce, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }

        return new PumpEnactResult(injector).success(true).enacted(true);
    }

    public PumpEnactResult acknowledgeAlerts() {
        long time = System.currentTimeMillis();
        try {
            delegate.acknowledgeAlerts();
            addSuccessToHistory(time, PodHistoryEntryType.AcknowledgeAlerts, null);
        } catch (Exception ex) {
            String comment = handleAndTranslateException(ex);
            addFailureToHistory(time, PodHistoryEntryType.AcknowledgeAlerts, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }
        return new PumpEnactResult(injector).success(true).enacted(true);
    }

    public PumpEnactResult getPodInfo(PodInfoType podInfoType) {
        long time = System.currentTimeMillis();
        try {
            // TODO how can we return the PodInfo response?
            // This method is useless unless we return the PodInfoResponse,
            // because the pod state we keep, doesn't get updated from a PodInfoResponse.
            // We use StatusResponses for that, which can be obtained from the getPodStatus method
            PodInfoResponse podInfo = delegate.getPodInfo(podInfoType);
            addSuccessToHistory(time, PodHistoryEntryType.GetPodInfo, podInfo);
            return new PumpEnactResult(injector).success(true).enacted(true);
        } catch (Exception ex) {
            String comment = handleAndTranslateException(ex);
            addFailureToHistory(time, PodHistoryEntryType.GetPodInfo, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }
    }

    public PumpEnactResult suspendDelivery() {
        try {
            delegate.suspendDelivery(isBasalBeepsEnabled());
        } catch (Exception ex) {
            String comment = handleAndTranslateException(ex);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }

        return new PumpEnactResult(injector).success(true).enacted(true);
    }

    public PumpEnactResult resumeDelivery() {
        try {
            delegate.resumeDelivery(isBasalBeepsEnabled());
        } catch (Exception ex) {
            String comment = handleAndTranslateException(ex);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }

        return new PumpEnactResult(injector).success(true).enacted(true);
    }

    // Updates the pods current time based on the device timezone and the pod's time zone
    public PumpEnactResult setTime() {
        long time = System.currentTimeMillis();
        try {
            delegate.setTime(isBasalBeepsEnabled());
            // Because set time actually suspends and then resumes delivery, TBR is implicitly cancelled
            reportImplicitlyCanceledTbr();
            addSuccessToHistory(time, PodHistoryEntryType.SetTime, null);
        } catch (Exception ex) {
            if ((ex instanceof OmnipodException) && !((OmnipodException) ex).isCertainFailure()) {
                reportImplicitlyCanceledTbr();
                addFailureToHistory(time, PodHistoryEntryType.SetTime, "Uncertain failure");
                return new PumpEnactResult(injector).success(false).enacted(false).comment(getStringResource(R.string.omnipod_error_set_time_failed_uncertain));
            }
            String comment = handleAndTranslateException(ex);
            reportImplicitlyCanceledTbr();
            addFailureToHistory(time, PodHistoryEntryType.SetTime, comment);
            return new PumpEnactResult(injector).success(false).enacted(false).comment(comment);
        }

        return new PumpEnactResult(injector).success(true).enacted(true);
    }

    public PodInfoRecentPulseLog readPulseLog() {
        PodInfoResponse response = delegate.getPodInfo(PodInfoType.RECENT_PULSE_LOG);
        return response.getPodInfo();
    }

    public OmnipodRileyLinkCommunicationManager getCommunicationService() {
        return delegate.getCommunicationService();
    }

    public DateTime getTime() {
        return delegate.getTime();
    }

    public boolean isBasalBeepsEnabled() {
        return basalBeepsEnabled;
    }

    public boolean isBolusBeepsEnabled() {
        return bolusBeepsEnabled;
    }

    public boolean isSmbBeepsEnabled() {
        return smbBeepsEnabled;
    }

    public boolean isTbrBeepsEnabled() {
        return tbrBeepsEnabled;
    }

    public boolean isPodDebuggingOptionsEnabled() {
        return podDebuggingOptionsEnabled;
    }

    public boolean isTimeChangeEventEnabled() {
        return timeChangeEventEnabled;
    }

    public void addBolusToHistory(DetailedBolusInfo detailedBolusInfo) {
        long pumpId = addSuccessToHistory(detailedBolusInfo.date, PodHistoryEntryType.SetBolus, detailedBolusInfo.insulin + ";" + detailedBolusInfo.carbs);
        detailedBolusInfo.pumpId = pumpId;
        activePlugin.getActiveTreatments().addToHistoryTreatment(detailedBolusInfo, false);
    }

    private void reportImplicitlyCanceledTbr() {
        //TreatmentsPlugin plugin = TreatmentsPlugin.getPlugin();
        TreatmentsInterface plugin = activePlugin.getActiveTreatments();
        if (plugin.isTempBasalInProgress()) {
            aapsLogger.debug(LTag.PUMP, "Reporting implicitly cancelled TBR to Treatments plugin");

            long time = System.currentTimeMillis() - 1000;

            addSuccessToHistory(time, PodHistoryEntryType.CancelTemporaryBasal, null);

            TemporaryBasal temporaryBasal = new TemporaryBasal(injector) //
                    .date(time) //
                    .duration(0) //
                    .source(Source.PUMP);

            plugin.addToHistoryTempBasal(temporaryBasal);
        }
    }

    private long addSuccessToHistory(long requestTime, PodHistoryEntryType entryType, Object data) {
        return addToHistory(requestTime, entryType, data, true);
    }

    private long addFailureToHistory(long requestTime, PodHistoryEntryType entryType, Object data) {
        return addToHistory(requestTime, entryType, data, false);
    }

    private long addToHistory(long requestTime, PodHistoryEntryType entryType, Object data, boolean success) {
        OmnipodHistoryRecord omnipodHistoryRecord = new OmnipodHistoryRecord(requestTime, entryType.getCode());

        if (data != null) {
            if (data instanceof String) {
                omnipodHistoryRecord.setData((String) data);
            } else {
                omnipodHistoryRecord.setData(aapsOmnipodUtil.getGsonInstance().toJson(data));
            }
        }

        omnipodHistoryRecord.setSuccess(success);
        omnipodHistoryRecord.setPodSerial(podStateManager.hasPodState() ? String.valueOf(podStateManager.getAddress()) : "None");

        databaseHelper.createOrUpdate(omnipodHistoryRecord);

        return omnipodHistoryRecord.getPumpId();
    }

    private void handleSetupActionResult(PodInitActionType podInitActionType, PodInitReceiver podInitReceiver, SetupActionResult res, long time, Profile profile) {
        String comment = null;
        switch (res.getResultType()) {
            case FAILURE: {
                aapsLogger.error(LTag.PUMP, "Setup action failed: illegal setup progress: {}", res.getPodProgressStatus());
                comment = getStringResource(R.string.omnipod_driver_error_invalid_progress_state, res.getPodProgressStatus());
            }
            break;
            case VERIFICATION_FAILURE: {
                aapsLogger.error(LTag.PUMP, "Setup action verification failed: caught exception", res.getException());
                comment = getStringResource(R.string.omnipod_driver_error_setup_action_verification_failed);
            }
            break;
        }

        if (podInitActionType == PodInitActionType.PairAndPrimeWizardStep) {
            addToHistory(time, PodHistoryEntryType.PairAndPrime, comment, res.getResultType().isSuccess());
        } else {
            addToHistory(time, PodHistoryEntryType.FillCannulaSetBasalProfile, res.getResultType().isSuccess() ? profile.getBasalValues() : comment, res.getResultType().isSuccess());
        }

        podInitReceiver.returnInitTaskStatus(podInitActionType, res.getResultType().isSuccess(), comment);
    }

    private String handleAndTranslateException(Exception ex) {
        String comment;

        if (ex instanceof OmnipodException) {
            if (ex instanceof ActionInitializationException || ex instanceof CommandInitializationException) {
                comment = getStringResource(R.string.omnipod_driver_error_invalid_parameters);
            } else if (ex instanceof CommunicationException) {
                if (((CommunicationException) ex).getType() == CommunicationException.Type.TIMEOUT) {
                    comment = getStringResource(R.string.omnipod_driver_error_communication_failed_timeout);
                } else {
                    comment = getStringResource(R.string.omnipod_driver_error_communication_failed_unexpected_exception);
                }
            } else if (ex instanceof CrcMismatchException) {
                comment = getStringResource(R.string.omnipod_driver_error_crc_mismatch);
            } else if (ex instanceof IllegalPacketTypeException) {
                comment = getStringResource(R.string.omnipod_driver_error_invalid_packet_type);
            } else if (ex instanceof IllegalPodProgressException || ex instanceof IllegalDeliveryStatusException) {
                comment = getStringResource(R.string.omnipod_driver_error_invalid_progress_state);
            } else if (ex instanceof IllegalVersionResponseTypeException) {
                comment = getStringResource(R.string.omnipod_driver_error_invalid_response);
            } else if (ex instanceof IllegalResponseException) {
                comment = getStringResource(R.string.omnipod_driver_error_invalid_response);
            } else if (ex instanceof IllegalMessageSequenceNumberException) {
                comment = getStringResource(R.string.omnipod_driver_error_invalid_message_sequence_number);
            } else if (ex instanceof IllegalMessageAddressException) {
                comment = getStringResource(R.string.omnipod_driver_error_invalid_message_address);
            } else if (ex instanceof MessageDecodingException) {
                comment = getStringResource(R.string.omnipod_driver_error_message_decoding_failed);
            } else if (ex instanceof NonceOutOfSyncException) {
                comment = getStringResource(R.string.omnipod_driver_error_nonce_out_of_sync);
            } else if (ex instanceof NonceResyncException) {
                comment = getStringResource(R.string.omnipod_driver_error_nonce_resync_failed);
            } else if (ex instanceof NotEnoughDataException) {
                comment = getStringResource(R.string.omnipod_driver_error_not_enough_data);
            } else if (ex instanceof PodFaultException) {
                FaultEventCode faultEventCode = ((PodFaultException) ex).getFaultEvent().getFaultEventCode();
                showPodFaultErrorDialog(faultEventCode);
                comment = createPodFaultErrorMessage(faultEventCode);
            } else if (ex instanceof PodReturnedErrorResponseException) {
                comment = getStringResource(R.string.omnipod_driver_error_pod_returned_error_response);
            } else {
                // Shouldn't be reachable
                comment = getStringResource(R.string.omnipod_driver_error_unexpected_exception_type, ex.getClass().getName());
            }
            aapsLogger.error(LTag.PUMP, String.format("Caught OmnipodException[certainFailure=%s] from OmnipodManager (user-friendly error message: %s)", ((OmnipodException) ex).isCertainFailure(), comment), ex);
        } else {
            comment = getStringResource(R.string.omnipod_driver_error_unexpected_exception_type, ex.getClass().getName());
            aapsLogger.error(LTag.PUMP, String.format("Caught unexpected exception type[certainFailure=false] from OmnipodManager (user-friendly error message: %s)", comment), ex);
        }

        return comment;
    }

    private String createPodFaultErrorMessage(FaultEventCode faultEventCode) {
        String comment;
        comment = getStringResource(R.string.omnipod_driver_error_pod_fault,
                ByteUtil.convertUnsignedByteToInt(faultEventCode.getValue()), faultEventCode.name());
        return comment;
    }

    private void sendEvent(Event event) {
        rxBus.send(event);
    }

    private void showPodFaultErrorDialog(FaultEventCode faultEventCode) {
        showErrorDialog(createPodFaultErrorMessage(faultEventCode), null);
    }

    private void showPodFaultErrorDialog(FaultEventCode faultEventCode, Integer sound) {
        showErrorDialog(createPodFaultErrorMessage(faultEventCode), sound);
    }

    private void showErrorDialog(String message) {
        showErrorDialog(message, null);
    }

    private void showErrorDialog(String message, Integer sound) {
        Intent intent = new Intent(context, ErrorHelperActivity.class);
        intent.putExtra("soundid", sound);
        intent.putExtra("status", message);
        intent.putExtra("title", resourceHelper.gs(R.string.error));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private void showNotification(String message, int urgency, Integer sound) {
        Notification notification = new Notification( //
                Notification.OMNIPOD_PUMP_ALARM, //
                message, //
                urgency);
        if (sound != null) {
            notification.soundId = sound;
        }
        sendEvent(new EventNewNotification(notification));
    }

    private String getStringResource(int id, Object... args) {
        return resourceHelper.gs(id, args);
    }

    public static BasalSchedule mapProfileToBasalSchedule(Profile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile can not be null");
        }
        Profile.ProfileValue[] basalValues = profile.getBasalValues();
        if (basalValues == null) {
            throw new IllegalArgumentException("Basal values can not be null");
        }
        List<BasalScheduleEntry> entries = new ArrayList<>();
        for (Profile.ProfileValue basalValue : basalValues) {
            entries.add(new BasalScheduleEntry(PumpType.Insulet_Omnipod.determineCorrectBasalSize(basalValue.value),
                    Duration.standardSeconds(basalValue.timeAsSeconds)));
        }

        return new BasalSchedule(entries);
    }
}
