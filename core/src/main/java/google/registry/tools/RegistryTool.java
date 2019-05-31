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

package google.registry.tools;

import com.google.common.collect.ImmutableMap;
import google.registry.tools.javascrap.PopulateNullRegistrarFieldsCommand;
import google.registry.tools.javascrap.RemoveIpAddressCommand;

/** Container class to create and run remote commands against a Datastore instance. */
public final class RegistryTool {

  /**
   * Available commands.
   *
   * <p><b>Note:</b> If changing the command-line name of any commands below, remember to resolve
   * any invocations in scripts (e.g. PDT, ICANN reporting).
   */
  public static final ImmutableMap<String, Class<? extends Command>> COMMAND_MAP =
      new ImmutableMap.Builder<String, Class<? extends Command>>()
          .put("canonicalize_labels", CanonicalizeLabelsCommand.class)
          .put("check_domain", CheckDomainCommand.class)
          .put("check_domain_claims", CheckDomainClaimsCommand.class)
          .put("convert_idn", ConvertIdnCommand.class)
          .put("count_domains", CountDomainsCommand.class)
          .put("create_anchor_tenant", CreateAnchorTenantCommand.class)
          .put("create_cdns_tld", CreateCdnsTld.class)
          .put("create_contact", CreateContactCommand.class)
          .put("create_domain", CreateDomainCommand.class)
          .put("create_host", CreateHostCommand.class)
          .put("create_premium_list", CreatePremiumListCommand.class)
          .put("create_registrar", CreateRegistrarCommand.class)
          .put("create_registrar_groups", CreateRegistrarGroupsCommand.class)
          .put("create_reserved_list", CreateReservedListCommand.class)
          .put("create_sandbox_tld", CreateSandboxTldCommand.class)
          .put("create_tld", CreateTldCommand.class)
          .put("curl", CurlCommand.class)
          .put("delete_allocation_tokens", DeleteAllocationTokensCommand.class)
          .put("delete_domain", DeleteDomainCommand.class)
          .put("delete_host", DeleteHostCommand.class)
          .put("delete_premium_list", DeletePremiumListCommand.class)
          .put("delete_reserved_list", DeleteReservedListCommand.class)
          .put("delete_tld", DeleteTldCommand.class)
          .put("deploy_invoicing_pipeline", DeployInvoicingPipelineCommand.class)
          .put("deploy_spec11_pipeline", DeploySpec11PipelineCommand.class)
          .put("encrypt_escrow_deposit", EncryptEscrowDepositCommand.class)
          .put("execute_epp", ExecuteEppCommand.class)
          .put("generate_allocation_tokens", GenerateAllocationTokensCommand.class)
          .put("generate_dns_report", GenerateDnsReportCommand.class)
          .put("generate_escrow_deposit", GenerateEscrowDepositCommand.class)
          .put("generate_lordn", GenerateLordnCommand.class)
          .put("generate_zone_files", GenerateZoneFilesCommand.class)
          .put("get_allocation_token", GetAllocationTokenCommand.class)
          .put("get_claims_list", GetClaimsListCommand.class)
          .put("get_contact", GetContactCommand.class)
          .put("get_domain", GetDomainCommand.class)
          .put("get_history_entries", GetHistoryEntriesCommand.class)
          .put("get_host", GetHostCommand.class)
          .put("get_keyring_secret", GetKeyringSecretCommand.class)
          .put("get_operation_status", GetOperationStatusCommand.class)
          .put("get_registrar", GetRegistrarCommand.class)
          .put("get_resource_by_key", GetResourceByKeyCommand.class)
          .put("get_routing_map", GetRoutingMapCommand.class)
          .put("get_schema", GetSchemaCommand.class)
          .put("get_schema_tree", GetSchemaTreeCommand.class)
          .put("get_tld", GetTldCommand.class)
          .put("ghostryde", GhostrydeCommand.class)
          .put("hash_certificate", HashCertificateCommand.class)
          .put("import_datastore", ImportDatastoreCommand.class)
          .put("list_cursors", ListCursorsCommand.class)
          .put("list_datastore_operations", ListDatastoreOperationsCommand.class)
          .put("list_domains", ListDomainsCommand.class)
          .put("list_hosts", ListHostsCommand.class)
          .put("list_premium_lists", ListPremiumListsCommand.class)
          .put("list_registrars", ListRegistrarsCommand.class)
          .put("list_reserved_lists", ListReservedListsCommand.class)
          .put("list_tlds", ListTldsCommand.class)
          .put("load_snapshot", LoadSnapshotCommand.class)
          .put("load_test", LoadTestCommand.class)
          .put("lock_domain", LockDomainCommand.class)
          .put("login", LoginCommand.class)
          .put("logout", LogoutCommand.class)
          .put("pending_escrow", PendingEscrowCommand.class)
          .put("populate_null_registrar_fields", PopulateNullRegistrarFieldsCommand.class)
          .put("registrar_contact", RegistrarContactCommand.class)
          .put("remove_ip_address", RemoveIpAddressCommand.class)
          .put("renew_domain", RenewDomainCommand.class)
          .put("resave_entities", ResaveEntitiesCommand.class)
          .put("resave_environment_entities", ResaveEnvironmentEntitiesCommand.class)
          .put("resave_epp_resource", ResaveEppResourceCommand.class)
          .put("send_escrow_report_to_icann", SendEscrowReportToIcannCommand.class)
          .put("set_num_instances", SetNumInstancesCommand.class)
          .put("setup_ote", SetupOteCommand.class)
          .put("uniform_rapid_suspension", UniformRapidSuspensionCommand.class)
          .put("unlock_domain", UnlockDomainCommand.class)
          .put("unrenew_domain", UnrenewDomainCommand.class)
          .put("update_allocation_tokens", UpdateAllocationTokensCommand.class)
          .put("update_cursors", UpdateCursorsCommand.class)
          .put("update_domain", UpdateDomainCommand.class)
          .put("update_kms_keyring", UpdateKmsKeyringCommand.class)
          .put("update_premium_list", UpdatePremiumListCommand.class)
          .put("update_registrar", UpdateRegistrarCommand.class)
          .put("update_reserved_list", UpdateReservedListCommand.class)
          .put("update_sandbox_tld", UpdateSandboxTldCommand.class)
          .put("update_server_locks", UpdateServerLocksCommand.class)
          .put("update_tld", UpdateTldCommand.class)
          .put("upload_claims_list", UploadClaimsListCommand.class)
          .put("validate_escrow_deposit", ValidateEscrowDepositCommand.class)
          .put("validate_login_credentials", ValidateLoginCredentialsCommand.class)
          .put("verify_ote", VerifyOteCommand.class)
          .put("whois_query", WhoisQueryCommand.class)
          .build();

  public static void main(String[] args) throws Exception {
    RegistryToolEnvironment.parseFromArgs(args).setup();
    try (RegistryCli cli = new RegistryCli("nomulus", COMMAND_MAP)) {
      cli.run(args);
    }
  }
}
