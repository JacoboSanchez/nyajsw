package io.jsl.nyajsw.platform.windows;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.platform.PlatformException;
import io.jsl.nyajsw.platform.ProcessJob;

import org.mockito.InOrder;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class WindowsProcessToolsTest {

    private static final KernelHandle JOB = new KernelHandle(7);
    private static final KernelHandle PROCESS = new KernelHandle(8);

    private ProcessApi api;
    private WindowsProcessTools tools;

    @BeforeMethod
    public void setUp() {
        api = mock(ProcessApi.class);
        when(api.createJob()).thenReturn(JOB);
        when(api.openProcessForJob(4242)).thenReturn(PROCESS);
        tools = new WindowsProcessTools(api);
    }

    @Test
    public void aJobIsCreatedToEndItsProcessesWhenClosed() {
        ProcessJob job = tools.createKillOnCloseJob().orElseThrow();

        InOrder order = inOrder(api);
        order.verify(api).createJob();
        order.verify(api).setKillOnClose(JOB);
        job.close();
        job.close();
        verify(api, times(1)).close(JOB);
    }

    @Test
    public void aJobThatCannotBeMadeKillOnCloseIsClosedAgainAndTheFailureSaysWhat() {
        doThrow(new PlatformException("SetInformationJobObject(kill on close): Access is denied (Win32 error 5)"))
                .when(api).setKillOnClose(JOB);

        PlatformException failure = expectThrows(PlatformException.class, () -> tools.createKillOnCloseJob());

        assertEquals(failure.getMessage(), "cannot create a job object for the application:"
                + " SetInformationJobObject(kill on close): Access is denied (Win32 error 5)");
        verify(api).close(JOB);
    }

    @Test
    public void aJobThatCannotBeCreatedSaysWhatAndClosesNothing() {
        when(api.createJob()).thenThrow(new PlatformException("CreateJobObject: Not enough memory (Win32 error 8)"));

        PlatformException failure = expectThrows(PlatformException.class, () -> tools.createKillOnCloseJob());

        assertEquals(failure.getMessage(), "cannot create a job object for the application: CreateJobObject: Not enough"
                + " memory (Win32 error 8)");
        verify(api, never()).close(org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void aProcessIsOpenedAssignedAndItsHandleClosed() {
        ProcessJob job = tools.createKillOnCloseJob().orElseThrow();

        job.add(4242);

        InOrder order = inOrder(api);
        order.verify(api).openProcessForJob(4242);
        order.verify(api).assign(JOB, PROCESS);
        order.verify(api).close(PROCESS);
    }

    @Test
    public void aProcessThatCannotBeAssignedHasItsHandleClosedToo() {
        doThrow(new PlatformException("AssignProcessToJobObject: Access is denied (Win32 error 5)"))
                .when(api).assign(JOB, PROCESS);
        ProcessJob job = tools.createKillOnCloseJob().orElseThrow();

        PlatformException failure = expectThrows(PlatformException.class, () -> job.add(4242));

        assertTrue(failure.getMessage().startsWith("cannot put process 4242 in the job object: "), failure.getMessage());
        verify(api).close(PROCESS);
    }

    @Test
    public void terminatingEndsEveryProcessWithTheCodeOfAForcedEnd() {
        ProcessJob job = tools.createKillOnCloseJob().orElseThrow();

        job.terminate();

        verify(api).terminate(JOB, WindowsProcessTools.TERMINATED);
        assertEquals(WindowsProcessTools.TERMINATED, 1);
    }

    @Test
    public void aClosedJobTakesNoMoreWork() {
        ProcessJob job = tools.createKillOnCloseJob().orElseThrow();
        job.close();

        expectThrows(IllegalStateException.class, () -> job.add(4242));
        expectThrows(IllegalStateException.class, job::terminate);
        verify(api, never()).openProcessForJob(4242);
    }

    @Test
    public void aNativeLibraryThatCannotBeLoadedNamesTheTemporaryDirectory() {
        when(api.createJob()).thenThrow(new UnsatisfiedLinkError("no jnidispatch"));

        PlatformException failure = expectThrows(PlatformException.class, () -> tools.createKillOnCloseJob());

        assertTrue(failure.getMessage().contains("the native library (JNA) cannot be loaded"), failure.getMessage());
    }
}
