package com.tirth.digest;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

public final class Mailer {

    // Static so a warm Lambda container reuses the client rather than rebuilding its HTTP stack.
    private static final SesV2Client SES = SesV2Client.create();

    private final String senderAddress;
    private final String recipientAddress;

    public Mailer(String senderAddress, String recipientAddress) {
        this.senderAddress = senderAddress;
        this.recipientAddress = recipientAddress;
    }

    public String send(String subject, String plainTextBody, String htmlBody) {
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(senderAddress)
                .destination(Destination.builder().toAddresses(recipientAddress).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data(subject).build())
                                // Supplying both parts makes SES send multipart/alternative, so a
                                // client that cannot render HTML still receives readable text.
                                .body(Body.builder()
                                        .text(Content.builder().data(plainTextBody).build())
                                        .html(Content.builder().data(htmlBody).build())
                                        .build())
                                .build())
                        .build())
                .build();

        return SES.sendEmail(request).messageId();
    }
}
