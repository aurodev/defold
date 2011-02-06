package com.dynamo.cr.common.providers.test;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.ext.MessageBodyReader;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Test;

import com.dynamo.cr.common.providers.JsonProviders.ProtobufMessageBodyReader;
import com.dynamo.cr.common.providers.JsonProviders.ProtobufMessageBodyWriter;
import com.dynamo.cr.protocol.proto.Protocol.ApplicationInfo;
import com.dynamo.cr.protocol.proto.Protocol.BranchStatus;
import com.dynamo.cr.protocol.proto.Protocol.BranchStatus.State;
import com.google.protobuf.Message;

public class JsonProvidersTest {

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void testWriteReadTo1() throws WebApplicationException, IOException {
        ApplicationInfo.Builder b = ApplicationInfo.newBuilder();
        b.setName("Test Application");
        b.setVersion("1.0");
        b.setSize(1234);

        ProtobufMessageBodyWriter writer = new ProtobufMessageBodyWriter();
        ApplicationInfo message = b.build();
        long size = writer.getSize(message, null, null, null, null);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        writer.writeTo(message, null, null, null, null, null, stream);
        assertEquals(size, stream.size());

        ObjectMapper m = new ObjectMapper();
        JsonNode node = m.readValue(stream.toString(), JsonNode.class);
        assertEquals(message.getName(), node.get("name").getValueAsText());
        assertEquals(message.getVersion(), node.get("version").getValueAsText());
        assertEquals(message.getSize(), node.get("size").getValueAsInt());

        ByteArrayInputStream inStream = new ByteArrayInputStream(node.toString().getBytes());
        MessageBodyReader reader = new ProtobufMessageBodyReader();
        Message message2 = (Message) reader.readFrom(message.getClass(), null, null, null, null, inStream);
        assertEquals(message, message2);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Test
    public void testWriteTo2() throws WebApplicationException, IOException {
        BranchStatus.Builder b = BranchStatus.newBuilder();
        b.setName("a name");
        b.setBranchState(State.DIRTY);
        b.addFileStatus(BranchStatus.Status.newBuilder().setName("foo.cpp").setOriginal("bar.cpp").setStatus("M").build());
        b.setCommitsAhead(4);
        b.setCommitsBehind(3);

        ProtobufMessageBodyWriter writer = new ProtobufMessageBodyWriter();
        BranchStatus message = b.build();
        long size = writer.getSize(message, null, null, null, null);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        writer.writeTo(message, null, null, null, null, null, stream);
        assertEquals(size, stream.size());

        ObjectMapper m = new ObjectMapper();
        JsonNode node = m.readValue(stream.toString(), JsonNode.class);
        assertEquals(message.getName(), node.get("Name").getValueAsText());
        assertEquals(message.getBranchState().getValueDescriptor().getName(), node.get("BranchState").getValueAsText());
        assertEquals(1, node.get("FileStatus").size());
        JsonNode fileStatus = node.get("FileStatus").get(0);
        assertEquals("foo.cpp", fileStatus.get("Name").getValueAsText());
        assertEquals(message.getCommitsAhead(), node.get("CommitsAhead").getValueAsInt());
        assertEquals(message.getCommitsBehind(), node.get("CommitsBehind").getValueAsInt());

        ByteArrayInputStream inStream = new ByteArrayInputStream(node.toString().getBytes());
        MessageBodyReader reader = new ProtobufMessageBodyReader();
        Message message2 = (Message) reader.readFrom(message.getClass(), null, null, null, null, inStream);
        assertEquals(message, message2);
    }
}
