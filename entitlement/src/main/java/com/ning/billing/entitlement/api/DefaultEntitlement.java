/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.entitlement.api;

import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;

import com.ning.billing.ErrorCode;
import com.ning.billing.catalog.api.BillingActionPolicy;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.Plan;
import com.ning.billing.catalog.api.PlanPhase;
import com.ning.billing.catalog.api.PriceList;
import com.ning.billing.catalog.api.Product;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.clock.Clock;
import com.ning.billing.entitlement.EntitlementService;
import com.ning.billing.entitlement.block.BlockingChecker;
import com.ning.billing.entitlement.dao.BlockingStateDao;
import com.ning.billing.subscription.api.SubscriptionBase;
import com.ning.billing.subscription.api.user.SubscriptionBaseApiException;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.callcontext.InternalCallContext;
import com.ning.billing.util.callcontext.InternalCallContextFactory;
import com.ning.billing.util.entity.EntityBase;
import com.ning.billing.util.svcapi.junction.DefaultBlockingState;

public class DefaultEntitlement extends EntityBase implements Entitlement {

    private final EntitlementDateHelper dateHelper;
    private final SubscriptionBase subscriptionBase;
    private final InternalCallContextFactory internalCallContextFactory;
    private final Clock clock;
    private final EntitlementState state;
    private final BlockingState entitlementBlockingState;
    private final BlockingChecker checker;
    private final UUID accountId;
    private final String externalKey;
    private final DateTimeZone accountTimeZone;
    private final BlockingStateDao blockingStateDao;

    public DefaultEntitlement(final EntitlementDateHelper dateHelper, final SubscriptionBase subscriptionBase, final UUID accountId,
                              final String externalKey, final EntitlementState state, final BlockingState entitlementBlockingState, final DateTimeZone accountTimeZone,
                              final InternalCallContextFactory internalCallContextFactory,
                              final BlockingStateDao blockingStateDao,
                              final Clock clock, final BlockingChecker checker) {
        super(subscriptionBase.getId(), subscriptionBase.getCreatedDate(), subscriptionBase.getUpdatedDate());
        this.dateHelper = dateHelper;
        this.subscriptionBase = subscriptionBase;
        this.accountId = accountId;
        this.externalKey = externalKey;
        this.state = state;
        this.entitlementBlockingState = entitlementBlockingState;
        this.accountTimeZone = accountTimeZone;
        this.internalCallContextFactory = internalCallContextFactory;
        this.clock = clock;
        this.checker = checker;
        this.blockingStateDao = blockingStateDao;
    }

    // STEPH_ENT should be remove but beatrix tests need to be changed
    public SubscriptionBase getSubscriptionBase() {
        return subscriptionBase;
    }

    @Override
    public UUID getBaseEntitlementId() {
        return subscriptionBase.getId();
    }

    @Override
    public UUID getBundleId() {
        return subscriptionBase.getBundleId();
    }

    @Override
    public UUID getAccountId() {
        return accountId;
    }

    @Override
    public String getExternalKey() {
        return externalKey;
    }

    @Override
    public EntitlementState getState() {
        return state;
    }

    @Override
    public EntitlementSourceType getSourceType() {
        return subscriptionBase.getSourceType();
    }

    @Override
    public LocalDate getEffectiveStartDate() {
        return new LocalDate(subscriptionBase.getStartDate(), accountTimeZone);
    }

    @Override
    public LocalDate getEffectiveEndDate() {
        if (entitlementBlockingState != null && entitlementBlockingState.getStateName().equals(DefaultEntitlementApi.ENT_STATE_CANCELLED)) {
            return new LocalDate(entitlementBlockingState.getEffectiveDate(), accountTimeZone);
        }
        return null;
        //return subscriptionBase.getEndDate() != null ? new LocalDate(subscriptionBase.getEndDate(), accountTimeZone) : null;
    }

    @Override
    public LocalDate getRequestedEndDate() {
        // STEPH_ENT
        return null; //subscriptionBase.;
    }

    @Override
    public Product getProduct() {
        return subscriptionBase.getCurrentPlan().getProduct();
    }

    @Override
    public Plan getPlan() {
        return subscriptionBase.getCurrentPlan();
    }

    @Override
    public PriceList getPriceList() {
        return subscriptionBase.getCurrentPriceList();
    }

    @Override
    public PlanPhase getCurrentPhase() {
        return subscriptionBase.getCurrentPhase();
    }

    @Override
    public ProductCategory getProductCategory() {
        return subscriptionBase.getCategory();
    }

    @Override
    public Product getLastActiveProduct() {
        return subscriptionBase.getLastActiveProduct();
    }

    @Override
    public Plan getLastActivePlan() {
        return subscriptionBase.getLastActivePlan();
    }

    @Override
    public PriceList getLastActivePriceList() {
        return subscriptionBase.getLastActivePriceList();
    }

    @Override
    public ProductCategory getLastActiveProductCategory() {
        return subscriptionBase.getLastActiveCategory();
    }


    @Override
    public boolean cancelEntitlementWithPolicy(final EntitlementActionPolicy entitlementPolicy, final CallContext callContext) throws EntitlementApiException {
        final LocalDate cancellationDate = getLocalDateFromEntitlementPolicy(entitlementPolicy);
        return cancelEntitlementWithDate(cancellationDate, callContext);
    }

    @Override
    public boolean cancelEntitlementWithDate(final LocalDate localCancelDate, final CallContext callContext) throws EntitlementApiException {

        if (state == EntitlementState.CANCELLED) {
            throw new EntitlementApiException(ErrorCode.SUB_CANCEL_BAD_STATE, getId(), EntitlementState.CANCELLED);
        }
        final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(accountId, callContext);
        final DateTime effectiveCancelDate = dateHelper.fromLocalDateAndReferenceTime(localCancelDate, subscriptionBase.getStartDate(), contextWithValidAccountRecordId);
        try {
            subscriptionBase.cancel(null, callContext);
            blockingStateDao.setBlockingState(new DefaultBlockingState(getId(), BlockingStateType.SUBSCRIPTION, DefaultEntitlementApi.ENT_STATE_CANCELLED, EntitlementService.ENTITLEMENT_SERVICE_NAME, true, true, false, effectiveCancelDate), clock, contextWithValidAccountRecordId);
            return localCancelDate.compareTo(new LocalDate(clock.getUTCNow())) <= 0;
        } catch (SubscriptionBaseApiException e) {
            throw new EntitlementApiException(e);
        }
    }


    @Override
    public boolean cancelEntitlementWithPolicyOverrideBillingPolicy(final EntitlementActionPolicy entitlementPolicy, final BillingActionPolicy billingPolicy, final CallContext callContext) throws EntitlementApiException {
        final LocalDate cancellationDate = getLocalDateFromEntitlementPolicy(entitlementPolicy);
        return cancelEntitlementWithDateOverrideBillingPolicy(cancellationDate, billingPolicy, callContext);
    }

    @Override
    public boolean cancelEntitlementWithDateOverrideBillingPolicy(final LocalDate localCancelDate, final BillingActionPolicy billingPolicy, final CallContext callContext) throws EntitlementApiException {

        if (state == EntitlementState.CANCELLED) {
            throw new EntitlementApiException(ErrorCode.SUB_CANCEL_BAD_STATE, getId(), EntitlementState.CANCELLED);
        }
        final InternalCallContext contextWithValidAccountRecordId = internalCallContextFactory.createInternalCallContext(accountId, callContext);
        final LocalDate effectiveLocalDate = new LocalDate(localCancelDate, accountTimeZone);
        final DateTime effectiveDate = dateHelper.fromLocalDateAndReferenceTime(effectiveLocalDate, subscriptionBase.getStartDate(), contextWithValidAccountRecordId);
        try {
            subscriptionBase.cancelWithPolicy(null, billingPolicy, callContext);
            blockingStateDao.setBlockingState(new DefaultBlockingState(getId(), BlockingStateType.SUBSCRIPTION, DefaultEntitlementApi.ENT_STATE_CANCELLED, EntitlementService.ENTITLEMENT_SERVICE_NAME, true, true, false, effectiveDate), clock, contextWithValidAccountRecordId);
            return effectiveLocalDate.compareTo(new LocalDate(clock.getUTCNow())) <= 0;
        } catch (SubscriptionBaseApiException e) {
            throw new EntitlementApiException(e);
        }
    }

    private LocalDate getLocalDateFromEntitlementPolicy(final EntitlementActionPolicy entitlementPolicy) {
        final LocalDate cancellationDate;
        switch (entitlementPolicy) {
            case IMM:
                cancellationDate = new LocalDate(clock.getUTCNow(), accountTimeZone);
                break;
            case EOT:
                cancellationDate = subscriptionBase.getChargedThroughDate() != null ? new LocalDate(subscriptionBase.getChargedThroughDate(), accountTimeZone) : new LocalDate(clock.getUTCNow(), accountTimeZone);
                break;
            default:
                throw new RuntimeException("Unsupported policy " + entitlementPolicy);
        }
        return cancellationDate;
    }


    @Override
    public void uncancel(final CallContext context) throws EntitlementApiException {
        //To change body of implemented methods use File | Settings | File Templates.
    }


    @Override
    public boolean changePlan(final String productName, final BillingPeriod billingPeriod, final String priceList, final LocalDate localDate, final CallContext callContext) throws EntitlementApiException {

        if (state != EntitlementState.ACTIVE) {
            throw new EntitlementApiException(ErrorCode.SUB_CHANGE_NON_ACTIVE, getId(), state);
        }

        final InternalCallContext context = internalCallContextFactory.createInternalCallContext(accountId, callContext);
        final DateTime requestedDate = dateHelper.fromLocalDateAndReferenceTime(localDate, subscriptionBase.getStartDate(), context);
        try {
            checker.checkBlockedChange(subscriptionBase, context);
            return subscriptionBase.changePlan(productName, billingPeriod, priceList, requestedDate, callContext);
        } catch (BlockingApiException e) {
            throw new EntitlementApiException(e, e.getCode(), e.getMessage());
        } catch (SubscriptionBaseApiException e) {
            throw new EntitlementApiException(e);
        }
    }

    @Override
    public boolean changePlanOverrideBillingPolicy(final String productName, final BillingPeriod billingPeriod, final String priceList, final LocalDate localDate, final BillingActionPolicy actionPolicy, final CallContext callContext) throws EntitlementApiException {

        if (state != EntitlementState.ACTIVE) {
            throw new EntitlementApiException(ErrorCode.SUB_CHANGE_NON_ACTIVE, getId(), state);
        }

        final InternalCallContext context = internalCallContextFactory.createInternalCallContext(accountId, callContext);
        final DateTime requestedDate = dateHelper.fromLocalDateAndReferenceTime(localDate, subscriptionBase.getStartDate(), context);
        try {
            checker.checkBlockedChange(subscriptionBase, context);
            return subscriptionBase.changePlanWithPolicy(productName, billingPeriod, priceList, requestedDate, actionPolicy, callContext);
        } catch (BlockingApiException e) {
            throw new EntitlementApiException(e, e.getCode(), e.getMessage());
        } catch (SubscriptionBaseApiException e) {
            throw new EntitlementApiException(e);
        }
    }

    @Override
    public boolean block(final String serviceName, final LocalDate effectiveDate, final CallContext context) throws EntitlementApiException {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public boolean unblock(final String serviceName, final LocalDate effectiveDate, final CallContext context) throws EntitlementApiException {
        return false;  //To change body of implemented methods use File | Settings | File Templates.
    }

}
