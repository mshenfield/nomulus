// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.util.CollectionUtils.findDuplicates;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.template.soy.data.SoyMapData;
import google.registry.model.domain.DomainBase;
import google.registry.tools.soy.RenewDomainSoyInfo;
import google.registry.util.Clock;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/** A command to renew domain(s) via EPP. */
@Parameters(separators = " =", commandDescription = "Renew domain(s) via EPP.")
final class RenewDomainCommand extends MutatingEppToolCommand {

  @Parameter(
      names = {"-p", "--period"},
      description = "Number of years to renew the registration for (defaults to 1).")
  private int period = 1;

  @Parameter(description = "Names of the domains to renew.", required = true)
  private List<String> mainParameters;

  @Inject
  Clock clock;

  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormat.forPattern("YYYY-MM-dd");

  @Override
  protected void initMutatingEppToolCommand() {
    String duplicates = Joiner.on(", ").join(findDuplicates(mainParameters));
    checkArgument(duplicates.isEmpty(), "Duplicate domain arguments found: '%s'", duplicates);
    checkArgument(period < 10, "Cannot renew domains for 10 or more years");
    DateTime now = clock.nowUtc();
    for (String domainName : mainParameters) {
      Optional<DomainBase> domainOptional =
          loadByForeignKey(DomainBase.class, domainName, now);
      checkArgumentPresent(domainOptional, "Domain '%s' does not exist or is deleted", domainName);
      setSoyTemplate(RenewDomainSoyInfo.getInstance(), RenewDomainSoyInfo.RENEWDOMAIN);
      DomainBase domain = domainOptional.get();
      addSoyRecord(
          domain.getCurrentSponsorClientId(),
          new SoyMapData(
              "domainName", domain.getFullyQualifiedDomainName(),
              "expirationDate", domain.getRegistrationExpirationTime().toString(DATE_FORMATTER),
              "period", String.valueOf(period)));
    }
  }
}
