// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.beust.jcommander.Parameter;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.domain.token.AllocationToken;
import java.util.List;

/** Shared base class for commands to update or delete allocation tokens. */
abstract class UpdateOrDeleteAllocationTokensCommand extends ConfirmingCommand
    implements CommandWithRemoteApi {

  @Parameter(
      names = {"-p", "--prefix"},
      description =
          "Act on all allocation tokens with this prefix, otherwise use '--tokens' to specify "
              + "exact tokens(s) to act on")
  protected String prefix;

  @Parameter(
      names = {"--tokens"},
      description =
          "Comma-separated list of tokens to act on; otherwise use '--prefix' to act on all tokens "
              + "with a given prefix")
  protected List<String> tokens;

  @Parameter(
      names = {"--dry_run"},
      description = "Do not actually update or delete the tokens; defaults to false")
  protected boolean dryRun;

  protected ImmutableSet<Key<AllocationToken>> getTokenKeys() {
    checkArgument(
        tokens == null ^ prefix == null,
        "Must provide one of --tokens or --prefix, not both / neither");
    if (tokens != null) {
      return tokens.stream()
          .map(token -> Key.create(AllocationToken.class, token))
          .collect(toImmutableSet());
    } else {
      checkArgument(!prefix.isEmpty(), "Provided prefix should not be blank");
      return ofy().load().type(AllocationToken.class).keys().list().stream()
          .filter(key -> key.getName().startsWith(prefix))
          .collect(toImmutableSet());
    }
  }
}
