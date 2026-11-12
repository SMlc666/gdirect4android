package com.smlc.gdirectandroid;

import android.content.Context;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class TransferQueueTest {
    private Context context;
    private TransferQueue queue;

    @Before public void setUp() {
        context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("gdirect-transfers-v2.sqlite");
        queue=new TransferQueue(context);
    }
    @After public void tearDown() {
        queue.close();
        context.deleteDatabase("gdirect-transfers-v2.sqlite");
    }

    @Test public void resumeAllCoversEveryJobBeyondOldTwoHundredLimit() throws Exception {
        List<String> ids=new ArrayList<>();
        for(int i=0;i<225;i++) {
            TransferQueue.Job j=queue.add("remote_"+i,"file "+i,"",
                "content://test/file"+i,"wifi","142.250.0.1",1,1024,8);
            ids.add(j.id);
            if(i%3==0)assertTrue(queue.move(j.id,TransferQueue.QUEUED,TransferQueue.PAUSED));
            else if(i%3==1)assertTrue(queue.move(j.id,TransferQueue.QUEUED,TransferQueue.FAILED));
        }
        assertEquals(225,queue.list().length());
        assertEquals(225,queue.resumeAll(7,"cellular","142.250.1.1"));
        for(String id:ids) {
            TransferQueue.Job j=queue.get(id);
            assertEquals(TransferQueue.QUEUED,j.state);
            assertEquals(7,j.network);
            assertEquals("142.250.1.1",j.ips);
        }
    }

    @Test public void resumeAllDoesNotTouchRunningOrCompleted() {
        TransferQueue.Job running=queue.add("remoteA","A","","content://test/a",
            "wifi","142.250.0.1",1,1024,8);
        TransferQueue.Job completed=queue.add("remoteB","B","","content://test/b",
            "wifi","142.250.0.1",1,1024,8);
        assertTrue(queue.move(running.id,TransferQueue.QUEUED,TransferQueue.RUNNING));
        assertTrue(queue.move(completed.id,TransferQueue.QUEUED,TransferQueue.COMPLETED));
        assertEquals(0,queue.resumeAll(7,"cellular","142.250.1.1"));
        assertEquals(TransferQueue.RUNNING,queue.get(running.id).state);
        assertEquals(TransferQueue.COMPLETED,queue.get(completed.id).state);
    }
}
