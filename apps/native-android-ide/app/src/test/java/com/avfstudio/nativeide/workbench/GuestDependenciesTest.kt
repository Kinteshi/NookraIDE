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
}
