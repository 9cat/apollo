package io.muun.apollo.domain.action.operation;

import io.muun.apollo.data.db.base.ElementNotFoundException;
import io.muun.apollo.data.db.incoming_swap.IncomingSwapDao;
import io.muun.apollo.data.db.incoming_swap.IncomingSwapHtlcDao;
import io.muun.apollo.data.db.incoming_swap.IncomingSwapHtlcDb;
import io.muun.apollo.data.db.operation.OperationDao;
import io.muun.apollo.data.db.public_profile.PublicProfileDao;
import io.muun.apollo.data.db.submarine_swap.SubmarineSwapDao;
import io.muun.apollo.data.external.NotificationService;
import io.muun.apollo.data.preferences.TransactionSizeRepository;
import io.muun.apollo.domain.action.incoming_swap.VerifyFulfillableAction;
import io.muun.apollo.domain.model.IncomingSwap;
import io.muun.apollo.domain.model.NextTransactionSize;
import io.muun.apollo.domain.model.Operation;
import io.muun.common.model.OperationDirection;
import io.muun.common.rx.ObservableFn;

import rx.Observable;
import timber.log.Timber;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class CreateOperationAction {

    private final TransactionSizeRepository transactionSizeRepository;
    private final OperationDao operationDao;
    private final PublicProfileDao publicProfileDao;
    private final SubmarineSwapDao submarineSwapDao;
    private final IncomingSwapDao incomingSwapDao;
    private final IncomingSwapHtlcDao incomingSwapHtlcDao;
    private final VerifyFulfillableAction verifyFulfillable;

    private final NotificationService notificationService;

    /**
     * Create a new Operation in the local database, and update the transaction size vector.
     */
    @Inject
    public CreateOperationAction(final TransactionSizeRepository transactionSizeRepository,
                                 final OperationDao operationDao,
                                 final PublicProfileDao publicProfileDao,
                                 final SubmarineSwapDao submarineSwapDao,
                                 final NotificationService notificationService,
                                 final IncomingSwapDao incomingSwapDao,
                                 final IncomingSwapHtlcDao incomingSwapHtlcDao,
                                 final VerifyFulfillableAction verifyFulfillable) {

        this.transactionSizeRepository = transactionSizeRepository;
        this.operationDao = operationDao;
        this.publicProfileDao = publicProfileDao;
        this.submarineSwapDao = submarineSwapDao;
        this.notificationService = notificationService;
        this.incomingSwapDao = incomingSwapDao;
        this.incomingSwapHtlcDao = incomingSwapHtlcDao;
        this.verifyFulfillable = verifyFulfillable;
    }

    public Observable<Operation> action(Operation operation,
                                        NextTransactionSize nextTransactionSize) {
        return saveOperation(operation)
                .map(savedOperation -> {
                    Timber.d("Updating next transaction size estimation");
                    transactionSizeRepository.setTransactionSize(nextTransactionSize);

                    if (savedOperation.direction == OperationDirection.INCOMING) {
                        notificationService.showNewOperationNotification(savedOperation);
                    }

                    return savedOperation;
                })
                .flatMap(savedOperation -> {
                    final Observable<Operation> continuation = Observable.just(savedOperation);

                    if (savedOperation.incomingSwap != null) {
                        return verifyFulfillable.action(savedOperation.incomingSwap)
                                .andThen(continuation);
                    } else {
                        return continuation;
                    }
                })
                .doOnCompleted(() -> {
                    // We have a bug where it seems sometimes operations are not persisted
                    // but we get no error reports from it, so this adds a bit of sanity
                    // checking to see if we can identify the bug properly.

                    // First we verify the op exists
                    final Throwable fetchOpError = operationDao.fetchByHid(operation.getHid())
                            .first()
                            .toCompletable()
                            .get();
                    if (fetchOpError != null) {
                        Timber.e("Missing op after create with id = %i", operation.getHid());
                    }

                    if (operation.incomingSwap != null) {
                        // Then if it's an incoming swap, we verify we can find it by uuid

                        final String incomingSwapUuid = operation.incomingSwap.houstonUuid;
                        final Throwable t = operationDao.fetchByIncomingSwapUuid(incomingSwapUuid)
                                .first()
                                .toCompletable()
                                .get();

                        if (t != null) {
                            Timber.e(
                                    "Failed to find op %i by incoming swap uuid %s",
                                    operation.getHid(),
                                    incomingSwapUuid
                            );
                        }

                    }
                });
    }

    /**
     * Save an operation to the database.
     */
    public Observable<Operation> saveOperation(Operation operation) {
        return operationDao.fetchByHid(operation.getHid())
                .first()
                .compose(ObservableFn.onTypedErrorResumeNext(
                        ElementNotFoundException.class,
                        error -> {
                            Observable<Operation> chain = Observable.just(operation);

                            if (operation.senderProfile != null) {
                                chain = chain.compose(ObservableFn.flatDoOnNext(ignored ->
                                        publicProfileDao.store(operation.senderProfile)
                                ));
                            }

                            if (operation.receiverProfile != null) {
                                chain = chain.compose(ObservableFn.flatDoOnNext(ignored ->
                                        publicProfileDao.store(operation.receiverProfile)
                                ));
                            }

                            if (operation.swap != null) {
                                chain = chain.compose(ObservableFn.flatDoOnNext(ignored ->
                                        submarineSwapDao.store(operation.swap)
                                ));
                            }

                            final IncomingSwap incomingSwap = operation.incomingSwap;
                            if (incomingSwap != null) {
                                chain = chain.compose(ObservableFn.flatDoOnNext(ignored ->
                                        incomingSwapDao.store(incomingSwap)
                                ));

                                if (incomingSwap.getHtlc() != null) {
                                    chain = chain.compose(ObservableFn.flatDoOnNext(ignored ->
                                            incomingSwapHtlcDao.store(new IncomingSwapHtlcDb(
                                                    incomingSwap.houstonUuid,
                                                    incomingSwap.getHtlc()
                                            ))
                                    ));
                                }
                            }

                            chain = chain.flatMap(operationDao::store);

                            return chain;
                        }
                ));
    }
}
