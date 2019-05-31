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

package google.registry.util;

import com.google.appengine.api.modules.ModulesService;
import com.google.appengine.api.modules.ModulesServiceFactory;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.SecureRandom;
import javax.inject.Named;
import javax.inject.Singleton;

/** Dagger module to provide instances of various utils classes. */
@Module
public abstract class UtilsModule {

  @Binds
  @Singleton
  abstract Sleeper provideSleeper(SystemSleeper sleeper);

  @Binds
  @Singleton
  abstract Clock provideClock(SystemClock clock);

  @Provides
  @Singleton
  static ModulesService provideModulesService() {
    return ModulesServiceFactory.getModulesService();
  }

  @Binds
  @Singleton
  abstract AppEngineServiceUtils provideAppEngineServiceUtils(
      AppEngineServiceUtilsImpl appEngineServiceUtilsImpl);


  @Singleton
  @Provides
  public static SecureRandom provideSecureRandom() {
    try {
      return SecureRandom.getInstance("NativePRNG");
    } catch (NoSuchAlgorithmException e) {
      throw new ProviderException(e);
    }
  }

  @Singleton
  @Provides
  @Named("base58StringGenerator")
  public static StringGenerator provideBase58StringGenerator(SecureRandom secureRandom) {
    return new RandomStringGenerator(StringGenerator.Alphabets.BASE_58, secureRandom);
  }

  @Singleton
  @Provides
  @Named("base64StringGenerator")
  public static StringGenerator provideBase64StringGenerator(SecureRandom secureRandom) {
    return new RandomStringGenerator(StringGenerator.Alphabets.BASE_64, secureRandom);
  }

  @Singleton
  @Provides
  @Named("digitOnlyStringGenerator")
  public static StringGenerator provideDigitsOnlyStringGenerator(SecureRandom secureRandom) {
    return new RandomStringGenerator(StringGenerator.Alphabets.DIGITS_ONLY, secureRandom);
  }
}
