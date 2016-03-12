/*
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
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

package org.killbill.billing.beatrix.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.api.TestApiListener.NextEvent;
import org.killbill.billing.beatrix.util.InvoiceChecker.ExpectedInvoiceItemCheck;
import org.killbill.billing.catalog.api.BillingActionPolicy;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.ProductCategory;
import org.killbill.billing.entitlement.api.DefaultEntitlement;
import org.killbill.billing.entitlement.api.Entitlement;
import org.killbill.billing.invoice.api.DryRunArguments;
import org.killbill.billing.invoice.api.DryRunType;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoiceItemType;
import org.killbill.billing.invoice.api.InvoiceStatus;
import org.killbill.billing.invoice.model.ExternalChargeInvoiceItem;
import org.killbill.billing.payment.api.Payment;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.subscription.api.user.DefaultSubscriptionBase;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestIntegrationInvoice extends TestIntegrationBase {

    //
    // Basic test with one subscription that verifies the behavior of using invoice dryRun api with no date
    //
    @Test(groups = "slow")
    public void testDryRunWithNoTargetDate() throws Exception {
        final int billingDay = 14;
        final DateTime initialCreationDate = new DateTime(2015, 5, 15, 0, 0, 0, 0, testTimeZone);
        // set clock to the initial start date
        clock.setTime(initialCreationDate);

        log.info("Beginning test with BCD of " + billingDay);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));

        int invoiceItemCount = 1;

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE, NextEvent.BLOCK NextEvent.INVOICE
        //
        DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        DefaultSubscriptionBase subscription = subscriptionDataFromSubscription(baseEntitlement.getSubscriptionBase());
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(initialCreationDate.toLocalDate(), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        // No end date for the trial item (fixed price of zero), and CTD should be today (i.e. when the trial started)
        invoiceChecker.checkChargedThroughDate(subscription.getId(), clock.getUTCToday(), callContext);

        final List<ExpectedInvoiceItemCheck> expectedInvoices = new ArrayList<ExpectedInvoiceItemCheck>();
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 6, 14), new LocalDate(2015, 7, 14), InvoiceItemType.RECURRING, new BigDecimal("249.95")));

        // This will verify that the upcoming Phase is found and the invoice is generated at the right date, with correct items
        DryRunArguments dryRun = new TestDryRunArguments(DryRunType.UPCOMING_INVOICE);
        Invoice dryRunInvoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), null, dryRun, callContext);
        invoiceChecker.checkInvoiceNoAudits(dryRunInvoice, callContext, expectedInvoices);

        // Move through time and verify we get the same invoice
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addDays(30);
        assertListenerStatus();
        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, callContext);
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, expectedInvoices);
        expectedInvoices.clear();

        // This will verify that the upcoming invoice notification is found and the invoice is generated at the right date, with correct items
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 7, 14), new LocalDate(2015, 8, 14), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        dryRunInvoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), null, dryRun, callContext);
        invoiceChecker.checkInvoiceNoAudits(dryRunInvoice, callContext, expectedInvoices);


        // Move through time and verify we get the same invoice
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addMonths(1);
        assertListenerStatus();
        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, callContext);
        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, expectedInvoices);
        expectedInvoices.clear();

        // One more time, this will verify that the upcoming invoice notification is found and the invoice is generated at the right date, with correct items
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 8, 14), new LocalDate(2015, 9, 14), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        dryRunInvoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), null, dryRun, callContext);
        invoiceChecker.checkInvoiceNoAudits(dryRunInvoice, callContext, expectedInvoices);
    }

    //
    // More sophisticated test with two non aligned subscriptions that verifies the behavior of using invoice dryRun api with no date
    // - The first subscription is an annual (SUBSCRIPTION aligned) whose billingDate is the first (we start on Jan 2nd to take into account the 30 days trial)
    // - The second subscription is a monthly (ACCOUNT aligned) whose billingDate is the 14 (we start on Dec 15 to also take into account the 30 days trial)
    //
    // The test verifies that the dryRun invoice with supplied date will always take into account the 'closest' invoice that should be generated
    //
    @Test(groups = "slow")
    public void testDryRunWithNoTargetDateAndMultipleNonAlignedSubscriptions() throws Exception {
        // Set in such a way that annual billing date will be the 1st
        final DateTime initialCreationDate = new DateTime(2014, 1, 2, 0, 0, 0, 0, testTimeZone);
        clock.setTime(initialCreationDate);

        // billing date for the monthly
        final int billingDay = 14;

        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));

        int invoiceItemCount = 1;

        // Create ANNUAL BP
        DefaultEntitlement baseEntitlementAnnual = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKeyAnnual", "Shotgun", ProductCategory.BASE, BillingPeriod.ANNUAL, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        DefaultSubscriptionBase subscriptionAnnual = subscriptionDataFromSubscription(baseEntitlementAnnual.getSubscriptionBase());
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(initialCreationDate.toLocalDate(), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        // No end date for the trial item (fixed price of zero), and CTD should be today (i.e. when the trial started)
        invoiceChecker.checkChargedThroughDate(subscriptionAnnual.getId(), clock.getUTCToday(), callContext);


        // Verify next dryRun invoice and then move the clock to that date to also verify real invoice is the same
        final List<ExpectedInvoiceItemCheck> expectedInvoices = new ArrayList<ExpectedInvoiceItemCheck>();
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2014, 2, 1), new LocalDate(2015, 2, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")));

        DryRunArguments dryRun = new TestDryRunArguments(DryRunType.UPCOMING_INVOICE);
        Invoice dryRunInvoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), null, dryRun, callContext);
        invoiceChecker.checkInvoiceNoAudits(dryRunInvoice, callContext, expectedInvoices);

        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        // 2014-2-1
        clock.addDays(30);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, expectedInvoices);
        expectedInvoices.clear();

        // Since we only have one subscription next dryRun will show the annual
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 2, 1), new LocalDate(2016, 2, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")));
        dryRunInvoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), null, dryRun, callContext);
        invoiceChecker.checkInvoiceNoAudits(dryRunInvoice, callContext, expectedInvoices);
        expectedInvoices.clear();

        // 2014-12-15
        final DateTime secondSubscriptionCreationDate = new DateTime(2014, 12, 15, 0, 0, 0, 0, testTimeZone);
        clock.setTime(secondSubscriptionCreationDate);

        // Create the monthly
        DefaultEntitlement baseEntitlementMonthly = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        DefaultSubscriptionBase subscriptionMonthly = subscriptionDataFromSubscription(baseEntitlementMonthly.getSubscriptionBase());
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, new ExpectedInvoiceItemCheck(secondSubscriptionCreationDate.toLocalDate(), null, InvoiceItemType.FIXED, new BigDecimal("0")));
        // No end date for the trial item (fixed price of zero), and CTD should be today (i.e. when the trial started)
        invoiceChecker.checkChargedThroughDate(subscriptionMonthly.getId(), clock.getUTCToday(), callContext);

        // Verify next dryRun invoice and then move the clock to that date to also verify real invoice is the same
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 1, 14), new LocalDate(2015, 2, 14), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        dryRunInvoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), null, dryRun, callContext);
        invoiceChecker.checkInvoiceNoAudits(dryRunInvoice, callContext, expectedInvoices);

        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        // 2015-1-14
        clock.addDays(30);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, expectedInvoices);
        expectedInvoices.clear();


        // We test first the next expected invoice for a specific subscription: We can see the targetDate is 2015-2-14 and not 2015-2-1
        final DryRunArguments dryRunWIthSubscription = new TestDryRunArguments(DryRunType.UPCOMING_INVOICE, null, null, null, null, null, null, subscriptionMonthly.getId(), null, null, null);
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 2, 14), new LocalDate(2015, 3, 14), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        dryRunInvoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), null, dryRunWIthSubscription, callContext);
        assertEquals(dryRunInvoice.getTargetDate(), new LocalDate(2015, 2, 14));
        invoiceChecker.checkInvoiceNoAudits(dryRunInvoice, callContext, expectedInvoices);
        expectedInvoices.clear();

        // Then we test first the next expected invoice at the account level
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 2, 1), new LocalDate(2016, 2, 1), InvoiceItemType.RECURRING, new BigDecimal("2399.95")));
        dryRunInvoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), null, dryRun, callContext);
        invoiceChecker.checkInvoiceNoAudits(dryRunInvoice, callContext, expectedInvoices);

        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        // 2015-2-1
        clock.addDays(18);
        assertListenerStatus();
        invoiceChecker.checkInvoice(account.getId(), invoiceItemCount++, callContext, expectedInvoices);
        expectedInvoices.clear();

        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 2, 14), new LocalDate(2015, 3, 14), InvoiceItemType.RECURRING, new BigDecimal("249.95")));
        dryRunInvoice = invoiceUserApi.triggerInvoiceGeneration(account.getId(), null, dryRun, callContext);
        invoiceChecker.checkInvoiceNoAudits(dryRunInvoice, callContext, expectedInvoices);
    }

    @Test(groups = "slow")
    public void testApplyCreditOnExistingBalance() throws Exception {
        final DateTime initialCreationDate = new DateTime(2015, 5, 15, 0, 0, 0, 0, testTimeZone);
        // set clock to the initial start date
        clock.setTime(initialCreationDate);

        final int billingDay = 14;

        log.info("Beginning test with BCD of " + billingDay);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));

        add_AUTO_PAY_OFF_Tag(account.getId(), ObjectType.ACCOUNT);

        createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);

        // Move through time and verify we get the same invoice
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE);
        clock.addDays(30);
        assertListenerStatus();

        final Collection<Invoice> invoices = invoiceUserApi.getUnpaidInvoicesByAccountId(account.getId(), new LocalDate(clock.getUTCNow(), account.getTimeZone()), callContext);
        assertEquals(invoices.size(), 1);

        final UUID unpaidInvoiceId = invoices.iterator().next().getId();
        final Invoice unpaidInvoice = invoiceUserApi.getInvoice(unpaidInvoiceId, callContext);
        assertTrue(unpaidInvoice.getBalance().compareTo(new BigDecimal("249.95")) == 0);

        final BigDecimal accountBalance1 = invoiceUserApi.getAccountBalance(account.getId(), callContext);
        assertTrue(accountBalance1.compareTo(new BigDecimal("249.95")) == 0);

        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        invoiceUserApi.insertCredit(account.getId(), new BigDecimal("300"), new LocalDate(clock.getUTCNow(), account.getTimeZone()), account.getCurrency(), true, null, callContext);
        assertListenerStatus();

        final BigDecimal accountBalance2 = invoiceUserApi.getAccountBalance(account.getId(), callContext);
        assertTrue(accountBalance2.compareTo(new BigDecimal("-50.05")) == 0);

        final Invoice unpaidInvoice2 = invoiceUserApi.getInvoice(unpaidInvoiceId, callContext);
        assertTrue(unpaidInvoice2.getBalance().compareTo(BigDecimal.ZERO) == 0);

        remove_AUTO_PAY_OFF_Tag(account.getId(), ObjectType.ACCOUNT);

        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addDays(31);
        assertListenerStatus();

        final BigDecimal accountBalance3 = invoiceUserApi.getAccountBalance(account.getId(), callContext);
        assertTrue(accountBalance3.compareTo(BigDecimal.ZERO) == 0);


        final List<Payment> payments = paymentApi.getAccountPayments(account.getId(), false, ImmutableList.<PluginProperty>of(), callContext);
        assertEquals(payments.size(), 1);

        final Payment payment = payments.get(0);
        assertTrue(payment.getPurchasedAmount().compareTo(new BigDecimal("199.90")) == 0);
    }

    @Test(groups = "slow")
    public void testDraftInvoice() throws Exception {

        final int billingDay = 14;
        final DateTime initialCreationDate = new DateTime(2015, 5, 15, 0, 0, 0, 0, testTimeZone);
        // set clock to the initial start date
        clock.setTime(initialCreationDate);

        log.info("Beginning test with BCD of " + billingDay);
        final Account account = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));

        int invoiceItemCount = 1;

        DefaultEntitlement baseEntitlement = createBaseEntitlementAndCheckForCompletion(account.getId(), "bundleKey", "Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY,  NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        DefaultSubscriptionBase subscription = subscriptionDataFromSubscription(baseEntitlement.getSubscriptionBase());

        final List<ExpectedInvoiceItemCheck> expectedInvoices = new ArrayList<ExpectedInvoiceItemCheck>();
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 6, 14), new LocalDate(2015, 7, 14), InvoiceItemType.RECURRING, new BigDecimal("249.95")));

        // Move through time and verify we get the same invoice
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addDays(30);
        assertListenerStatus();

        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, callContext);
        invoiceChecker.checkInvoice(invoices.get(1).getId(), callContext, expectedInvoices);
        expectedInvoices.clear();

        // This will verify that the upcoming invoice notification is found and the invoice is generated at the right date, with correct items
        expectedInvoices.add(new ExpectedInvoiceItemCheck(new LocalDate(2015, 7, 14), new LocalDate(2015, 8, 14), InvoiceItemType.RECURRING, new BigDecimal("249.95")));

        // add create external charge
        final LocalDate date = clock.getToday(account.getTimeZone());
        final List<InvoiceItem> invoiceItemList = new ArrayList<InvoiceItem>();
        ExternalChargeInvoiceItem item = new ExternalChargeInvoiceItem(null, account.getId(), subscription.getBundleId(), "", date, BigDecimal.TEN, account.getCurrency());
        invoiceItemList.add(item);
        final List<InvoiceItem> draftInvoiceItems = invoiceUserApi.insertExternalCharges(account.getId(), date, invoiceItemList, false, callContext);

        // add expected invoice
        final List<ExpectedInvoiceItemCheck> expectedDraftInvoices = new ArrayList<ExpectedInvoiceItemCheck>();
        expectedDraftInvoices.add(new ExpectedInvoiceItemCheck(InvoiceItemType.EXTERNAL_CHARGE, BigDecimal.TEN));

        // Move through time and verify invoices
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addMonths(1);
        assertListenerStatus();
        invoices = invoiceUserApi.getInvoicesByAccount(account.getId(), false, callContext);

        invoiceChecker.checkInvoice(invoices.get(2).getId(), callContext, expectedDraftInvoices);
        invoiceChecker.checkInvoice(invoices.get(3).getId(), callContext, expectedInvoices);

        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        invoiceUserApi.commitInvoice(draftInvoiceItems.get(0).getInvoiceId(), callContext);
        assertListenerStatus();

        final List<Payment> accountPayments = paymentApi.getAccountPayments(account.getId(), false, null, callContext);
        assertEquals(accountPayments.size(), 3);
        assertEquals(accountPayments.get(2).getPurchasedAmount(), new BigDecimal("10.000000000"));

    }

    @Test(groups = "slow")
    public void testParentInvoiceGeneration() throws Exception {

        final int billingDay = 14;
        final DateTime initialCreationDate = new DateTime(2015, 5, 15, 0, 0, 0, 0, testTimeZone);
        // set clock to the initial start date
        clock.setTime(initialCreationDate);

        log.info("Beginning test with BCD of " + billingDay);
        final Account parentAccount = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));
        final Account child1Account = createAccountWithNonOsgiPaymentMethod(getChildAccountData(billingDay, parentAccount.getId(), true));
        final Account child2Account = createAccountWithNonOsgiPaymentMethod(getChildAccountData(billingDay, parentAccount.getId(), true));


        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        DefaultEntitlement baseEntitlementChild1 = createBaseEntitlementAndCheckForCompletion(child1Account.getId(), "bundleKey1", "Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);
        DefaultEntitlement baseEntitlementChild2 = createBaseEntitlementAndCheckForCompletion(child2Account.getId(), "bundleKey2", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);

        // First Parent invoice over TRIAL period

        List<Invoice> parentInvoices = invoiceUserApi.getInvoicesByAccount(parentAccount.getId(), false, callContext);
        assertEquals(parentInvoices.size(), 1);

        Invoice parentInvoice = parentInvoices.get(0);
        assertEquals(parentInvoice.getNumberOfItems(), 2);
        assertEquals(parentInvoice.getStatus(), InvoiceStatus.DRAFT);
        assertTrue(parentInvoice.isParentInvoice());
        assertEquals(parentInvoice.getBalance().toString(), "0.00");

        // Moving a day the NotificationQ calls the commitInvoice. No payment is expected because balance is 0
        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        clock.addDays(1);
        assertListenerStatus();

        parentInvoice = invoiceUserApi.getInvoice(parentInvoice.getId(), callContext);
        assertEquals(parentInvoice.getStatus(), InvoiceStatus.COMMITTED);

        // Move through time and verify new parent Invoice. No payments are expected.
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.PHASE,
                                      NextEvent.INVOICE, NextEvent.INVOICE);
        clock.addMonths(1);
        assertListenerStatus();

        // Second Parent invoice over Recurring period
        parentInvoices = invoiceUserApi.getInvoicesByAccount(parentAccount.getId(), false, callContext);
        assertEquals(parentInvoices.size(), 2);

        parentInvoice = parentInvoices.get(1);
        assertEquals(parentInvoice.getNumberOfItems(), 2);
        assertEquals(parentInvoice.getStatus(), InvoiceStatus.DRAFT);
        assertTrue(parentInvoice.isParentInvoice());
        assertEquals(parentInvoice.getBalance().toString(), "279.90");

        // Check Child Balance. It should be > 0 here because Parent didn't pay yet.
        List<Invoice> child1Invoices = invoiceUserApi.getInvoicesByAccount(child1Account.getId(), false, callContext);
        assertEquals(child1Invoices.size(), 2);
        assertTrue(child1Invoices.get(1).getBalance().compareTo(BigDecimal.ZERO) > 0);

        // Moving a day the NotificationQ calls the commitInvoice. Now payment is expected
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addDays(1);
        assertListenerStatus();

        parentInvoice = invoiceUserApi.getInvoice(parentInvoice.getId(), callContext);
        assertEquals(parentInvoice.getStatus(), InvoiceStatus.COMMITTED);

        // Check Child Balance. It should be = 0 because parent invoice had already paid.
        child1Invoices = invoiceUserApi.getInvoicesByAccount(child1Account.getId(), false, callContext);
        assertEquals(child1Invoices.size(), 2);
        assertTrue(parentInvoice.getBalance().compareTo(BigDecimal.ZERO) == 0);
        assertTrue(child1Invoices.get(1).getBalance().compareTo(BigDecimal.ZERO) == 0);

    }

    @Test(groups = "slow")
    public void testParentInvoiceGenerationMultipleActionsSameDay() throws Exception {

        final int billingDay = 14;
        final DateTime initialCreationDate = new DateTime(2015, 5, 15, 0, 0, 0, 0, testTimeZone);

        // set clock to the initial start date
        clock.setTime(initialCreationDate);


        log.info("Beginning test with BCD of " + billingDay);
        final Account parentAccount = createAccountWithNonOsgiPaymentMethod(getAccountData(billingDay));
        final Account childAccount = createAccountWithNonOsgiPaymentMethod(getChildAccountData(billingDay, parentAccount.getId(), true));

        //
        // CREATE SUBSCRIPTION AND EXPECT BOTH EVENTS: NextEvent.CREATE NextEvent.INVOICE
        //
        DefaultEntitlement baseEntitlementChild = createBaseEntitlementAndCheckForCompletion(childAccount.getId(), "bundleKey1", "Pistol", ProductCategory.BASE, BillingPeriod.MONTHLY, NextEvent.CREATE, NextEvent.BLOCK, NextEvent.INVOICE);

        // Moving a day the NotificationQ calls the commitInvoice. No payment is expected because balance is 0
        busHandler.pushExpectedEvents(NextEvent.INVOICE);
        clock.addDays(1);
        assertListenerStatus();

        // Move through time and verify new parent Invoice. No payments are expected.
        busHandler.pushExpectedEvents(NextEvent.PHASE, NextEvent.INVOICE);
        clock.addMonths(1);
        assertListenerStatus();

        // check parent Invoice with child plan amount
        List<Invoice> parentInvoices = invoiceUserApi.getInvoicesByAccount(parentAccount.getId(), false, callContext);
        assertEquals(parentInvoices.size(), 2);

        Invoice parentInvoice = parentInvoices.get(1);
        assertEquals(parentInvoice.getNumberOfItems(), 1);
        assertEquals(parentInvoice.getStatus(), InvoiceStatus.DRAFT);
        assertTrue(parentInvoice.isParentInvoice());
        assertEquals(parentInvoice.getBalance().toString(), "29.95");

        // change plan
        busHandler.pushExpectedEvents(NextEvent.CHANGE, NextEvent.INVOICE);
        final Entitlement newChildEntitlement = baseEntitlementChild.changePlanOverrideBillingPolicy("Shotgun", BillingPeriod.MONTHLY, baseEntitlementChild.getLastActivePriceList().getName(), null, clock.getToday(childAccount.getTimeZone()), BillingActionPolicy.IMMEDIATE, null, callContext);
        assertListenerStatus();

        // check parent invoice. Expected to have the same invoice item but the amount updated
        parentInvoices = invoiceUserApi.getInvoicesByAccount(parentAccount.getId(), false, callContext);
        assertEquals(parentInvoices.size(), 2);

        parentInvoice = parentInvoices.get(1);
        assertEquals(parentInvoice.getNumberOfItems(), 1);
        assertEquals(parentInvoice.getStatus(), InvoiceStatus.DRAFT);
        assertTrue(parentInvoice.isParentInvoice());
        assertEquals(parentInvoice.getBalance().toString(), "235.29");

        // Moving a day the NotificationQ calls the commitInvoice. Now payment is expected
        busHandler.pushExpectedEvents(NextEvent.INVOICE, NextEvent.PAYMENT, NextEvent.INVOICE_PAYMENT);
        clock.addDays(1);
        assertListenerStatus();

        parentInvoice = invoiceUserApi.getInvoice(parentInvoice.getId(), callContext);
        assertEquals(parentInvoice.getStatus(), InvoiceStatus.COMMITTED);

    }
}
