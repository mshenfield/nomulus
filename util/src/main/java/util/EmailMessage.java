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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import java.util.Collection;
import java.util.Optional;
import javax.mail.internet.InternetAddress;

/** Value class representing the content and metadata of an email. */
@AutoValue
public abstract class EmailMessage {

  public abstract String subject();
  public abstract String body();
  public abstract ImmutableList<InternetAddress> recipients();
  public abstract InternetAddress from();
  public abstract Optional<InternetAddress> bcc();
  public abstract Optional<MediaType> contentType();
  public abstract Optional<Attachment> attachment();

  public static Builder newBuilder() {
    return new AutoValue_EmailMessage.Builder();
  }

  public static EmailMessage create(
      String subject, String body, InternetAddress recipient, InternetAddress from) {
    return newBuilder()
        .setSubject(subject)
        .setBody(body)
        .setRecipients(ImmutableList.of(recipient))
        .setFrom(from)
        .build();
  }

  /** Builder for {@link EmailMessage}. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setSubject(String subject);
    public abstract Builder setBody(String body);
    public abstract Builder setRecipients(Collection<InternetAddress> recipients);
    public abstract Builder setFrom(InternetAddress from);
    public abstract Builder setBcc(InternetAddress bcc);
    public abstract Builder setContentType(MediaType contentType);
    public abstract Builder setAttachment(Attachment attachment);

    abstract ImmutableList.Builder<InternetAddress> recipientsBuilder();

    public Builder addRecipient(InternetAddress value) {
      recipientsBuilder().add(value);
      return this;
    }

    public abstract EmailMessage build();
  }

  /** An attachment to the email, if one exists. */
  @AutoValue
  public abstract static class Attachment {

    public abstract MediaType contentType();
    public abstract String filename();
    public abstract String content();

    public static Builder newBuilder() {
      return new AutoValue_EmailMessage_Attachment.Builder();
    }

    /** Builder for {@link Attachment}. */
    @AutoValue.Builder
    public abstract static class Builder {

      public abstract Builder setContentType(MediaType contentType);
      public abstract Builder setFilename(String filename);
      public abstract Builder setContent(String content);
      public abstract Attachment build();
    }
  }
}
