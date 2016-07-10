// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////
package com.google.pubsub.kafka.sink;

import com.google.api.client.util.Maps;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.google.pubsub.kafka.common.ConnectorUtils;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PublishResponse;
import com.google.pubsub.v1.PubsubMessage;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/***
 * A {@link SinkTask} used by a {@link CloudPubSubSinkConnector} to write messages to
 * <a href="https://cloud.google.com/pubsub">Google Cloud Pub/Sub</a>.
 */
public class CloudPubSubSinkTask extends SinkTask {
  private static final Logger log = LoggerFactory.getLogger(CloudPubSubSinkTask.class);
  private static final int NUM_PUBLISHERS = 10;
  private static final int MAX_REQUEST_SIZE = (10<<20) - 1024; // Leave a little room for overhead.
  private static final int MAX_MESSAGES_PER_REQUEST = 1000;

  private String cpsTopic;
  private int minBatchSize;
  private CloudPubSubPublisher publisher;
  /** Maps a topic to another map which contains the outstanding futures per partition */
  private Map<String, Map<Integer, OutstandingFuturesForPartition>> allOutstandingFutures = Maps.newHashMap();
  /** Maps a topic to another map which contains the unpublished messages per partition */
  private Map<String, Map<Integer, UnpublishedMessagesForPartition>> allUnpublishedMessages = Maps.newHashMap();

  /**
   * Holds a list of the futures that have not been processed for a single partition.
   */
  private class OutstandingFuturesForPartition {
    public List<ListenableFuture<PublishResponse>> futures = new ArrayList<>();
  }

  /**
   * Holds a list of the unpublished messages for a single partition and also
   * holds the total size in bytes of the protobuf messages in the list.
   */
  private class UnpublishedMessagesForPartition {
    public List<PubsubMessage> messages = new ArrayList<>();
    public int size = 0;
  }

  public CloudPubSubSinkTask() {}

  @Override
  public String version() {
    // TODO(rramkumar): Why do we create a Connector object?
    return new CloudPubSubSinkConnector().version();
  }

  @Override
  public void start(Map<String, String> props) {
    this.cpsTopic =
        String.format(
            ConnectorUtils.TOPIC_FORMAT,
            props.get(CloudPubSubSinkConnector.CPS_PROJECT_CONFIG),
            props.get(CloudPubSubSinkConnector.CPS_TOPIC_CONFIG));
    this.minBatchSize = Integer.parseInt(props.get(CloudPubSubSinkConnector.CPS_MIN_BATCH_SIZE));
    log.info("Start connector task for topic " + cpsTopic + " min batch size = " + minBatchSize);
    this.publisher = new CloudPubSubRoundRobinPublisher(NUM_PUBLISHERS);
  }

  @Override
  public void put(Collection<SinkRecord> sinkRecords) {
    log.debug("Received " + sinkRecords.size() + " messages to send to CPS.");
    PubsubMessage.Builder builder = PubsubMessage.newBuilder();
    for (SinkRecord record : sinkRecords) {
      // Verify that the schema of the data coming in from Kafka Connect is of type ByteString.
      if (record.valueSchema().type() != Schema.Type.BYTES ||
          !record.valueSchema().name().equals(ConnectorUtils.SCHEMA_NAME)) {
        throw new DataException("Unexpected record of type " + record.valueSchema());
      }
      log.trace("Received record: " + record.toString());
      // TODO(rramkumar): Why does this need to be final?
      final Map<String, String> attributes = Maps.newHashMap();
      // We know this can be cast to ByteString because of the schema check above.
      ByteString value = (ByteString) record.value();
      attributes.put(ConnectorUtils.PARTITION_ATTRIBUTE, record.kafkaPartition().toString());
      // Get the total number of bytes in this message.
      int messageSize = value.size() + ConnectorUtils.PARTITION_ATTRIBUTE_SIZE
          + record.kafkaPartition().toString().length();
      // The key could possibly be null so we add the null check.
      if (record.key() != null) {;
        attributes.put(ConnectorUtils.KEY_ATTRIBUTE, record.key().toString());
        messageSize += ConnectorUtils.KEY_ATTRIBUTE_SIZE + (2 * record.key().toString().length());
      }
      PubsubMessage message = builder
          .setData(value)
          .putAllAttributes(attributes)
          .build();
      // Get a map containing all the unpublished messages per partition for this topic.
      Map<Integer, UnpublishedMessagesForPartition> unpublishedMessagesForTopic =
          allUnpublishedMessages.get(record.topic());
      if (unpublishedMessagesForTopic == null) {
        unpublishedMessagesForTopic = Maps.newHashMap();
        allUnpublishedMessages.put(record.topic(), unpublishedMessagesForTopic);
      }
      // Get the object containing the unpublished messages for the
      // specific topic and partition this Sink Record is associated with.
      UnpublishedMessagesForPartition unpublishedMessages = unpublishedMessagesForTopic.get(record.kafkaPartition());
      if (unpublishedMessages == null) {
        unpublishedMessages = new UnpublishedMessagesForPartition();
        unpublishedMessagesForTopic.put(record.kafkaPartition(), unpublishedMessages);
      }
      int newUnpublishedSize = unpublishedMessages.size + messageSize;
      // Publish messages in this partition if the total number of bytes goes over limit.
      if (newUnpublishedSize > MAX_REQUEST_SIZE) {
        publishMessagesForPartition(record.topic(), record.kafkaPartition(), unpublishedMessages.messages);
        newUnpublishedSize = messageSize;
      }
      unpublishedMessages.size = newUnpublishedSize;
      unpublishedMessages.messages.add(message);
      if (unpublishedMessages.messages.size() >= minBatchSize) {
        publishMessagesForPartition(record.topic(), record.kafkaPartition(), unpublishedMessages.messages);
      }
    }
  }

  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> partitionOffsets) {
    // Publish all messages that have not been published yet.
    for (Map.Entry<String, Map<Integer, UnpublishedMessagesForPartition>> entry :
        allUnpublishedMessages.entrySet()) {
      for (Map.Entry<Integer,UnpublishedMessagesForPartition> innerEntry :
          entry.getValue().entrySet()) {
        publishMessagesForPartition(
            entry.getKey(),
            innerEntry.getKey(),
            innerEntry.getValue().messages);
      }
    }
    allUnpublishedMessages.clear();
    // Process results of all the outstanding futures specified by each TopicPartition object.
    for (Map.Entry<TopicPartition, OffsetAndMetadata> partitionOffset :
        partitionOffsets.entrySet()) {
      log.debug("Received flush for partition " + partitionOffset.getKey().toString());
      Map<Integer, OutstandingFuturesForPartition> outstandingFuturesForTopic =
          allOutstandingFutures.get(partitionOffset.getKey().topic());
      if (outstandingFuturesForTopic == null) {
        continue;
      }
      OutstandingFuturesForPartition outstandingFutures = outstandingFuturesForTopic.get(partitionOffset.getKey().partition());
      if (outstandingFutures == null ) {
        continue;
      }
      try {
        for (ListenableFuture<PublishResponse> publishRequest : outstandingFutures.futures) {
          publishRequest.get();
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    allOutstandingFutures.clear();
  }

  @Override
  public void stop() {}

  private void publishMessagesForPartition(String topic, Integer partition, List<PubsubMessage> messages) {
    // Get a map containing all futures per partition for the passed in topic.
    Map<Integer, OutstandingFuturesForPartition> outstandingFuturesForTopic = allOutstandingFutures.get(topic);
    if (outstandingFuturesForTopic == null) {
      outstandingFuturesForTopic = Maps.newHashMap();
      allOutstandingFutures.put(topic, outstandingFuturesForTopic);
    }
    // Get the object containing the outstanding futures for this topic and partition..
    OutstandingFuturesForPartition outstandingFutures = outstandingFuturesForTopic.get(partition);
    if (outstandingFutures == null) {
      outstandingFutures = new OutstandingFuturesForPartition();
      outstandingFuturesForTopic.put(partition, outstandingFutures);
    }
    int startIndex = 0;
    int endIndex = Math.min(MAX_MESSAGES_PER_REQUEST, messages.size());
    PublishRequest.Builder builder = PublishRequest.newBuilder();
    // TODO(rramkumar): What is going on here?
    while (startIndex < messages.size()) {
      PublishRequest request = builder
          .setTopic(cpsTopic)
          .addAllMessages(messages.subList(startIndex, endIndex))
          .build();
      // TODO(rramkumar): Do we need builder.clear()?
      builder.clear();
      // log.info("Publishing: " + (endIndex - startIndex) + " messages");
      outstandingFutures.futures.add(publisher.publish(request));
      startIndex = endIndex;
      endIndex = Math.min(endIndex + MAX_MESSAGES_PER_REQUEST, messages.size());
    }
    messages.clear();
  }
}
