// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.flows.domain;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.pricing.PricingEngineProxy.getDomainFeeClass;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;

import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.FlowScope;
import google.registry.flows.custom.DomainPricingCustomLogic;
import google.registry.flows.custom.DomainPricingCustomLogic.CreatePriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.RenewPriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.RestorePriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.TransferPriceParameters;
import google.registry.flows.custom.DomainPricingCustomLogic.UpdatePriceParameters;
import google.registry.model.domain.fee.BaseFee;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.pricing.PremiumPricingEngine.DomainPrices;
import google.registry.model.registry.Registry;
import google.registry.pricing.PricingEngineProxy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * Provides pricing for create, renew, etc, operations, with call-outs that can be customized by
 * providing a {@link DomainPricingCustomLogic} implementation that operates on cross-TLD or per-TLD
 * logic.
 */
@FlowScope
public final class DomainPricingLogic {

  @Inject DomainPricingCustomLogic customLogic;

  @Inject
  DomainPricingLogic() {}

  /**
   * Returns a new create price for the pricer.
   *
   * <p>If {@code allocationToken} is present and the domain is non-premium, that discount will be
   * applied to the first year.
   */
  public FeesAndCredits getCreatePrice(
      Registry registry,
      String domainName,
      DateTime date,
      int years,
      boolean isAnchorTenant,
      Optional<AllocationToken> allocationToken)
      throws EppException {
    CurrencyUnit currency = registry.getCurrency();
    // Domain create cost is always zero for anchor tenants
    Money domainCreateCost =
        isAnchorTenant
            ? Money.of(currency, BigDecimal.ZERO)
            : getDomainCreateCostWithDiscount(domainName, date, years, allocationToken);
    BaseFee createFeeOrCredit = Fee.create(domainCreateCost.getAmount(), FeeType.CREATE);

    // Create fees for the cost and the EAP fee, if any.
    Fee eapFee = registry.getEapFeeFor(date);
    FeesAndCredits.Builder feesBuilder =
        new FeesAndCredits.Builder().setCurrency(currency).addFeeOrCredit(createFeeOrCredit);
    // Don't charge anchor tenants EAP fees.
    if (!isAnchorTenant && !eapFee.hasZeroCost()) {
      feesBuilder.addFeeOrCredit(eapFee);
    }

    // Apply custom logic to the create fee, if any.
    return customLogic.customizeCreatePrice(
        CreatePriceParameters.newBuilder()
            .setFeesAndCredits(feesBuilder.build())
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(date)
            .setYears(years)
            .build());
  }

  /** Returns a new renew price for the pricer. */
  @SuppressWarnings("unused")
  public FeesAndCredits getRenewPrice(
      Registry registry,
      String domainName,
      DateTime date,
      int years)
      throws EppException {
    Money renewCost = getDomainRenewCost(domainName, date, years);
    return customLogic.customizeRenewPrice(
        RenewPriceParameters.newBuilder()
            .setFeesAndCredits(
                new FeesAndCredits.Builder()
                    .setCurrency(registry.getCurrency())
                    .addFeeOrCredit(Fee.create(renewCost.getAmount(), FeeType.RENEW))
                    .build())
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(date)
            .setYears(years)
            .build());
  }

  /** Returns a new restore price for the pricer. */
  public FeesAndCredits getRestorePrice(Registry registry, String domainName, DateTime date)
      throws EppException {
    FeesAndCredits feesAndCredits =
        new FeesAndCredits.Builder()
            .setCurrency(registry.getCurrency())
            .addFeeOrCredit(
                Fee.create(getDomainRenewCost(domainName, date, 1).getAmount(), FeeType.RENEW))
            .addFeeOrCredit(
                Fee.create(registry.getStandardRestoreCost().getAmount(), FeeType.RESTORE))
            .build();
    return customLogic.customizeRestorePrice(
        RestorePriceParameters.newBuilder()
            .setFeesAndCredits(feesAndCredits)
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(date)
            .build());
  }

  /** Returns a new transfer price for the pricer. */
  public FeesAndCredits getTransferPrice(Registry registry, String domainName, DateTime date)
      throws EppException {
    Money renewCost = getDomainRenewCost(domainName, date, 1);
    return customLogic.customizeTransferPrice(
        TransferPriceParameters.newBuilder()
            .setFeesAndCredits(
                new FeesAndCredits.Builder()
                    .setCurrency(registry.getCurrency())
                    .addFeeOrCredit(Fee.create(renewCost.getAmount(), FeeType.RENEW))
                    .build())
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(date)
            .build());
  }

  /** Returns a new update price for the pricer. */
  public FeesAndCredits getUpdatePrice(Registry registry, String domainName, DateTime date)
      throws EppException {
    CurrencyUnit currency = registry.getCurrency();
    BaseFee feeOrCredit =
        Fee.create(Money.zero(registry.getCurrency()).getAmount(), FeeType.UPDATE);
    return customLogic.customizeUpdatePrice(
        UpdatePriceParameters.newBuilder()
            .setFeesAndCredits(
                new FeesAndCredits.Builder()
                    .setCurrency(currency)
                    .setFeesAndCredits(feeOrCredit)
                    .build())
            .setRegistry(registry)
            .setDomainName(InternetDomainName.from(domainName))
            .setAsOfDate(date)
            .build());
  }

  /** Returns the fee class for a given domain and date. */
  public Optional<String> getFeeClass(String domainName, DateTime date) {
    return getDomainFeeClass(domainName, date);
  }

  private Money getDomainCreateCostWithDiscount(
      String domainName, DateTime date, int years, Optional<AllocationToken> allocationToken) {
    DomainPrices domainPrices = PricingEngineProxy.getPricesForDomainName(domainName, date);
    checkArgument(
        !allocationToken.isPresent()
            || allocationToken.get().getDiscountFraction() == 0.0
            || !domainPrices.isPremium(),
        "A nonzero discount code cannot be applied to premium domains");
    Money oneYearCreateCost = domainPrices.getCreateCost();
    Money totalDomainCreateCost = oneYearCreateCost.multipliedBy(years);
    // If a discount is applicable, apply it only to the first year
    if (allocationToken.isPresent()) {
      Money discount =
          oneYearCreateCost.multipliedBy(
              allocationToken.get().getDiscountFraction(), RoundingMode.HALF_UP);
      totalDomainCreateCost = totalDomainCreateCost.minus(discount);
    }
    return totalDomainCreateCost;
  }
}
