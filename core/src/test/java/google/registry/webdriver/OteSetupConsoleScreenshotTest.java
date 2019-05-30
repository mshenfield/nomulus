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

package google.registry.webdriver;

import static google.registry.server.Fixture.BASIC;
import static google.registry.server.Route.route;

import com.googlecode.objectify.ObjectifyFilter;
import google.registry.model.ofy.OfyFilter;
import google.registry.module.frontend.FrontendServlet;
import google.registry.server.RegistryTestServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.By;

/** Registrar Console Screenshot Differ tests. */
@RunWith(RepeatableRunner.class)
public class OteSetupConsoleScreenshotTest extends WebDriverTestCase {

  @Rule
  public final TestServerRule server =
      new TestServerRule.Builder()
          .setRunfiles(RegistryTestServer.RUNFILES)
          .setRoutes(route("/registrar-ote-setup", FrontendServlet.class))
          .setFilters(ObjectifyFilter.class, OfyFilter.class)
          .setFixtures(BASIC)
          .setEmail("Marla.Singer@google.com")
          .build();

  @Test
  public void get_owner_fails() throws Throwable {
    driver.get(server.getUrl("/registrar-ote-setup"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("unauthorized");
  }

  @Test
  public void get_admin_succeeds() throws Throwable {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar-ote-setup"));
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("formEmpty");
    driver.findElement(By.id("clientId")).sendKeys("acmereg");
    driver.findElement(By.id("email")).sendKeys("acmereg@registry.example");
    driver.findElement(By.id("password")).sendKeys("StRoNgPaSsWoRd");
    driver.diffPage("formFilled");
    driver.findElement(By.id("submit-button")).click();
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("oteResult");
  }

  @Test
  public void get_admin_fails_badEmail() throws Throwable {
    server.setIsAdmin(true);
    driver.get(server.getUrl("/registrar-ote-setup"));
    driver.waitForElement(By.tagName("h1"));
    driver.findElement(By.id("clientId")).sendKeys("acmereg");
    driver.findElement(By.id("email")).sendKeys("bad email");
    driver.findElement(By.id("submit-button")).click();
    driver.waitForElement(By.tagName("h1"));
    driver.diffPage("oteResultFailed");
  }
}
