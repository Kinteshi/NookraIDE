package com.avfstudio.nativeide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestDependenciesTest {
    @Test
    fun anyOfADependencysCommandsCounts() {
        val states = dependencyStates(mapOf("git" to "/usr/bin/git", "conda" to null, "mamba" to "/home/u/miniforge3/bin/mamba", "uv" to null))
        assertEquals(DependencyState.Found("/usr/bin/git"), states[GuestDependency.Git])
        assertEquals(DependencyState.Found("/home/u/miniforge3/bin/mamba"), states[GuestDependency.Conda])
        assertEquals(DependencyState.Missing, states[GuestDependency.Uv])
        // Not asked about: unknown, never "missing".
        assertEquals(DependencyState.Unknown, states[GuestDependency.Ripgrep])
    }

    @Test
    fun recognizesDaemonErrorsForMissingPrograms() {
        assertEquals(GuestDependency.Ripgrep, missingDependencyIn("Could not start ripgrep: No such file or directory (os error 2)"))
        assertEquals(GuestDependency.Git, missingDependencyIn("could not start Git: No such file or directory (os error 2)"))
        assertEquals(GuestDependency.JupyterBridge, missingDependencyIn("tool 'jupyter' is not installed in the guest registry"))
        // Git ran but something else failed.
        assertEquals(null, missingDependencyIn("could not start Git credential prompt listener: Address in use"))
        assertEquals(null, missingDependencyIn("fatal: not a git repository"))
    }

    @Test
    fun installsCloseTheTerminalOnlyOnSuccess() {
        GuestDependency.entries.forEach { assertTrue(installCommand(it).endsWith(" && exit")) }
        val conda = installCommand(GuestDependency.Conda)
        assertTrue("sha256sum -c" in conda)
        assertTrue("releases/download/26.7.2-0/" in conda)
    }

    @Test
    fun condaEnvironmentsGetIpykernelFromConda() {
        val conda = "/home/u/miniconda3/bin/conda"
        assertEquals(
            "'$conda' install -y -p '/home/u/miniconda3/envs/fourteen' ipykernel",
            ipykernelInstallCommand("/home/u/miniconda3/envs/fourteen/bin/python", conda, uv = true),
        )
        assertEquals(
            "'$conda' install -y -p '/home/u/miniconda3' ipykernel",
            ipykernelInstallCommand("/home/u/miniconda3/bin/python", conda, uv = false),
        )
        // A venv elsewhere uses pip, then uv when the venv has no pip.
        assertEquals(
            "{ '/home/u/.venv/bin/python' -m pip install ipykernel || uv pip install --python '/home/u/.venv/bin/python' ipykernel; }",
            ipykernelInstallCommand("/home/u/.venv/bin/python", conda, uv = true),
        )
    }

    @Test
    fun tmpPlacementFollowsTheMountAndTheMask() {
        assertEquals(null, tmpPlacement(null, true))
        assertEquals(TmpPlacement.Memory, tmpPlacement(true, false))
        assertEquals(TmpPlacement.Memory, tmpPlacement(true, null))
        assertEquals(TmpPlacement.DiskAfterRestart, tmpPlacement(true, true))
        assertEquals(TmpPlacement.MovedToDisk, tmpPlacement(false, true))
        assertEquals(TmpPlacement.Disk, tmpPlacement(false, false))
    }

    @Test
    fun forwarderFixFollowsTheDaemonsReport() {
        assertEquals(null, forwarderFix(null, null))
        assertEquals(ForwarderFix.Missing, forwarderFix(false, false))
        assertEquals(ForwarderFix.AfterRestart, forwarderFix(true, false))
        assertEquals(ForwarderFix.Active, forwarderFix(true, true))
        assertEquals("sudo \"\$HOME/.local/bin/avf-native-daemon\" guest fix-port-forwarding --undo", FORWARDER_UNDO_COMMAND)
    }

    @Test
    fun interpretersMapToTheEnvironmentTyReads() {
        assertEquals(listOf("CONDA_PREFIX" to "/home/droid/miniconda3/envs/fourteen"), pythonEnvironmentVariables("/home/droid/miniconda3/envs/fourteen/bin/python"))
        assertEquals(listOf("CONDA_PREFIX" to "/home/droid/miniforge3"), pythonEnvironmentVariables("/home/droid/miniforge3/bin/python"))
        assertEquals(listOf("VIRTUAL_ENV" to "/home/droid/.venv"), pythonEnvironmentVariables("/home/droid/.venv/bin/python"))
        assertEquals(emptyList<Pair<String, String>>(), pythonEnvironmentVariables("/usr/bin/python3"))
        assertEquals(emptyList<Pair<String, String>>(), pythonEnvironmentVariables("python3"))
        assertEquals(listOf("fourteen", ".venv", "system", "python3", "Select Python"),
            listOf("/home/droid/miniconda3/envs/fourteen/bin/python", "/home/droid/.venv/bin/python", "/usr/bin/python3", "python3", "").map(::pythonEnvironmentName))
    }
}
