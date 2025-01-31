package io.muun.apollo.presentation.ui.signup;

import io.muun.apollo.domain.SignupDraftManager;
import io.muun.apollo.domain.action.SigninActions;
import io.muun.apollo.domain.action.base.ActionState;
import io.muun.apollo.domain.action.session.CreateLoginSessionAction;
import io.muun.apollo.domain.action.session.LogInAction;
import io.muun.apollo.domain.action.session.rc_only.LogInWithRcAction;
import io.muun.apollo.domain.errors.EmailNotRegisteredError;
import io.muun.apollo.domain.errors.InvalidChallengeSignatureError;
import io.muun.apollo.domain.model.LoginWithRc;
import io.muun.apollo.domain.model.SignupDraft;
import io.muun.apollo.domain.model.SignupStep;
import io.muun.apollo.presentation.analytics.AnalyticsEvent;
import io.muun.apollo.presentation.ui.base.BasePresenter;
import io.muun.apollo.presentation.ui.base.di.PerActivity;
import io.muun.apollo.presentation.ui.fragments.enter_password.EnterPasswordParentPresenter;
import io.muun.apollo.presentation.ui.fragments.enter_recovery_code.EnterRecoveryCodeParentPresenter;
import io.muun.apollo.presentation.ui.fragments.login_authorize.LoginAuthorizeParentPresenter;
import io.muun.apollo.presentation.ui.fragments.login_email.LoginEmailParentPresenter;
import io.muun.apollo.presentation.ui.fragments.rc_only_login.RcOnlyLoginParentPresenter;
import io.muun.apollo.presentation.ui.fragments.rc_only_login_auth.RcLoginEmailAuthorizeParentPresenter;
import io.muun.common.crypto.ChallengeType;
import io.muun.common.model.CreateSessionOk;
import io.muun.common.model.CreateSessionRcOk;
import io.muun.common.utils.Preconditions;

import android.os.Bundle;
import androidx.annotation.NonNull;
import rx.Observable;

import java.util.Objects;
import javax.inject.Inject;
import javax.validation.constraints.NotNull;

@PerActivity
public class SignupPresenter extends BasePresenter<SignupView> implements
        LoginEmailParentPresenter,
        LoginAuthorizeParentPresenter,
        EnterPasswordParentPresenter,
        EnterRecoveryCodeParentPresenter,
        RcOnlyLoginParentPresenter,
        RcLoginEmailAuthorizeParentPresenter {

    private SignupDraft signupDraft;

    private final SigninActions signinActions;
    private final CreateLoginSessionAction createLoginSession;
    private final LogInAction logIn;
    private final LogInWithRcAction logInWithRc;
    private final SignupDraftManager signupDraftManager;

    /**
     * Constructor.
     */
    @Inject
    public SignupPresenter(SigninActions signinActions,
                           CreateLoginSessionAction createLoginSession,
                           LogInAction logIn,
                           LogInWithRcAction logInWithRc,
                           SignupDraftManager signupDraftManager) {

        this.signinActions = signinActions;
        this.createLoginSession = createLoginSession;
        this.logIn = logIn;
        this.logInWithRc = logInWithRc;
        this.signupDraftManager = signupDraftManager;
    }

    @Override
    public void onViewCreated(Bundle savedInstanceState) {
        restoreSignupDraft();
    }

    @Override
    public void setUp(@NotNull Bundle arguments) {
        super.setUp(arguments);

        setUpCreateLoginSessionAction();
        setUpLoginAction();
        setUpLoginWithRcAction();
    }

    private void setUpCreateLoginSessionAction() {
        final Observable<?> observable = watchSubmitEmail()
                .filter(ActionState::isValue)   // Child presenters handle errors
                .map(ActionState::getValue)
                .doOnNext(createSessionOk -> {
                    signupDraft.setExistingUser(true);
                    signupDraft.setCanUseRecoveryCode(createSessionOk.canUseRecoveryCode());
                    navigateToStepFrom(SignupStep.LOGIN_WAIT_VERIFICATION, signupDraft.getStep());
                });

        subscribeTo(observable);
    }

    private void setUpLoginAction() {
        final Observable<?> observable = watchSubmitEnterPassword() // its same for SubmitEnterRC
                .filter(ActionState::isValue)   // Child presenters handle errors
                .map(ActionState::getValue)
                .doOnNext(ignored -> navigateToStepFrom(SignupStep.SYNC, signupDraft.getStep()));

        subscribeTo(observable);
    }

    private void setUpLoginWithRcAction() {
        final Observable<?> observable = watchLoginWithRcOnly()
                .filter(ActionState::isValue)   // Child presenters handle errors
                .map(ActionState::getValue)
                .doOnNext(createSessionOk -> {

                    signupDraft.setExistingUser(true);

                    if (createSessionOk.hasEmailSetup()) {
                        signupDraft.setEmail(createSessionOk.getOfuscatedEmail().get());
                        navigateToStepFrom(
                                SignupStep.LOGIN_RECOVERY_CODE_EMAIL_AUTH,
                                signupDraft.getStep()
                        );

                    } else {

                        navigateToStepFrom(SignupStep.SYNC, signupDraft.getStep());
                    }
                });

        subscribeTo(observable);
    }

    @NonNull
    public SignupDraft getSignupDraft() {
        return signupDraft;
    }

    @NonNull
    @Override
    public String getOfuscatedEmail() {
        return Objects.requireNonNull(signupDraft.getEmail());
    }

    /**
     * Jump the the appropriate step if signUp was underway.
     */
    public void resumeSignupIfStarted() {
        final SignupStep step = signupDraft.getStep();

        if (step != SignupStep.START) {
            navigateToStepFrom(step, SignupStep.START);
        }
    }

    /**
     * Begin the signUp flow.
     */
    public void startSignup() {
        checkStep(SignupStep.START);
        navigateToStepFrom(SignupStep.SYNC, signupDraft.getStep());
    }

    /**
     * Begin the signUp flow.
     */
    public void startLogin() {
        checkStep(SignupStep.START);
        navigateToStepFrom(SignupStep.LOGIN_EMAIL, signupDraft.getStep());
    }

    /**
     * Proceed after the email link was clicked.
     */
    @Override
    public void reportEmailVerified() {
        checkStep(SignupStep.LOGIN_WAIT_VERIFICATION);
        Preconditions.checkState(signupDraft.isExistingUser());

        navigateToStepFrom(SignupStep.LOGIN_PASSWORD, signupDraft.getStep());
    }

    @Override
    public boolean canUseRecoveryCodeToLogin() {
        checkStep(SignupStep.LOGIN_PASSWORD);
        return signupDraft.getCanUseRecoveryCode();
    }

    @Override
    public void useRecoveryCodeToLogin() {
        checkStep(SignupStep.LOGIN_PASSWORD);
        navigateToStepFrom(SignupStep.LOGIN_RECOVERY_CODE, signupDraft.getStep());
    }

    @Override
    public void useRecoveryCodeOnlyLogin() {
        checkStep(SignupStep.LOGIN_EMAIL);
        navigateToStepFrom(SignupStep.LOGIN_RECOVERY_CODE_ONLY, signupDraft.getStep());
    }

    @Override
    public void reportRcLoginEmailVerified() {
        checkStep(SignupStep.LOGIN_RECOVERY_CODE_EMAIL_AUTH);
        Preconditions.checkState(signupDraft.isExistingUser());
        Preconditions.checkNotNull(signupDraft.getLoginWithRc());

        signupDraft.getLoginWithRc().setKeysetFetchNeeded(true);
        navigateToStepFrom(SignupStep.SYNC, signupDraft.getStep());
    }

    @NonNull
    @Override
    public Observable<ActionState<CreateSessionOk>> watchSubmitEmail() {
        return createLoginSession.getState();
    }

    @Override
    public Observable<ActionState<Void>> watchSubmitEnterPassword() {
        return logIn.getState();
    }

    @Override
    public Observable<ActionState<Void>> watchSubmitEnterRecoveryCode() {
        return logIn.getState();
    }

    @NonNull
    @Override
    public Observable<ActionState<CreateSessionRcOk>> watchLoginWithRcOnly() {
        return logInWithRc.getState();
    }

    /**
     * Submit the entered email.
     */
    @Override
    public void submitEmail(@NonNull String email) {
        checkStep(SignupStep.LOGIN_EMAIL);

        signupDraft.setEmail(email);
        createLoginSession.run(email);
    }

    @Override
    public void submitEnterPassword(String password) {
        checkStep(SignupStep.LOGIN_PASSWORD);
        logIn.run(ChallengeType.PASSWORD, password);
    }

    @Override
    public void submitEnterRecoveryCode(String recoveryCode) {
        logIn.run(ChallengeType.RECOVERY_CODE, recoveryCode);
    }

    @Override
    public void loginWithRcOnly(@NonNull String recoveryCode) {
        logInWithRc.run(recoveryCode);
        signupDraft.setLoginWithRc(new LoginWithRc(recoveryCode, false));
    }

    @Override
    public void cancelEnterEmail() {
        checkStep(SignupStep.LOGIN_EMAIL);

        createLoginSession.reset();
        signinActions.clearSession();

        navigateToStepFrom(SignupStep.START, signupDraft.getStep());
    }

    @Override
    public void cancelEmailVerification() {
        checkStep(SignupStep.LOGIN_WAIT_VERIFICATION);
        Preconditions.checkState(signupDraft.isExistingUser());

        signinActions.clearSession();
        navigateToStepFrom(SignupStep.LOGIN_EMAIL, signupDraft.getStep());
    }

    @Override
    public void cancelEnterPassword() {
        checkStep(SignupStep.LOGIN_PASSWORD);
        analytics.report(new AnalyticsEvent.E_SIGN_IN_ABORTED());

        logIn.reset();
        signinActions.clearSession();
        navigateToStepFrom(SignupStep.START, signupDraft.getStep());
    }

    @Override
    public void cancelEnterRecoveryCode() {
        checkStep(SignupStep.LOGIN_RECOVERY_CODE);
        navigateToStepFrom(SignupStep.LOGIN_PASSWORD, signupDraft.getStep());
    }

    @Override
    public void cancelLoginWithRcOnly() {
        checkStep(SignupStep.LOGIN_RECOVERY_CODE_ONLY);

        signinActions.clearSession();
        signupDraftManager.clear();
        restoreSignupDraft();
        navigateToStepFrom(SignupStep.LOGIN_EMAIL, signupDraft.getStep());
    }

    @Override
    public void cancelRcLoginEmailAuth() {
        checkStep(SignupStep.LOGIN_RECOVERY_CODE_EMAIL_AUTH);
        navigateToStepFrom(SignupStep.LOGIN_RECOVERY_CODE_ONLY, signupDraft.getStep());
    }

    @Override
    public void handleError(Throwable error) {
        if (error instanceof InvalidChallengeSignatureError
                || error instanceof EmailNotRegisteredError) {
            // This error is caught by child presenters. By checking, we avoid double-handling it.
            // TODO this is not enforced by design, we must find a better solution.
        } else {
            super.handleError(error);
        }
    }

    /**
     * Complete the signUp process and navigate to home screen.
     */
    public void reportSyncComplete() {
        checkStep(SignupStep.SYNC);

        if (signupDraft.isExistingUser()) {
            navigator.navigateToHome(getContext());

        } else {
            navigator.navigateNewUserToHome(getContext());
        }

        view.finishActivity();
    }

    /**
     * Navigate to SignupStep, specifying previous step.
     * Note: navigating to SYNC step requires passing previous step (aka from), for tracking
     * purposes.
     */
    private void navigateToStepFrom(SignupStep step, SignupStep previousStep) {
        // Make sure we start with a clean slate if we get back to the start screen
        if (step == SignupStep.START) {
            signupDraftManager.clear();
            restoreSignupDraft();
        } else {
            signupDraft.setStep(step);
            signupDraftManager.save(signupDraft);
        }

        view.changeStep(step, previousStep);
    }

    private void checkStep(SignupStep... allowed) {
        for (SignupStep signupStep : allowed) {
            if (signupDraft.getStep() == signupStep) {
                return;
            }
        }

        throw new IllegalStateException(signupDraft.getStep().name() + " step cannot do this");
    }

    private void restoreSignupDraft() {
        signupDraft = signupDraftManager.restore();
    }
}
