package link.infra.packwiz.installer.ui.gui;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BrowserPagesTest {
	@Test
	public void opensEachWebAddressOnceAndReportsFailuresWithoutBlockingOtherMods() throws Exception {
		String first = "https://mods.example/one";
		String broken = "https://mods.example/unavailable";
		String last = "http://mods.example/three";
		List<String> urls = Arrays.asList(first, first, "HTTPS://MODS.EXAMPLE/one", broken,
				"file:///local/mod.jar", "invalid address", "https:///missing-host", last);
		List<String> attempts = new ArrayList<>();

		List<String> failures = BrowserPages.openEach(urls, uri -> {
			attempts.add(uri.toString());
			if (uri.toString().equals(broken)) throw new IOException("Browser unavailable");
		});

		assertEquals(Arrays.asList(first, broken, last), attempts);
		assertEquals(4, failures.size());
		assertTrue(failures.get(0).startsWith(broken + "\n"));
		assertTrue(failures.get(1).startsWith("file:///local/mod.jar\n"));
		assertTrue(failures.get(2).startsWith("invalid address\n"));
		assertTrue(failures.get(3).startsWith("https:///missing-host\n"));

		assertTrue(BrowserPages.openEach(Collections.singletonList(broken),
				uri -> attempts.add(uri.toString())).isEmpty());
		assertEquals(Arrays.asList(first, broken, last, broken), attempts);
	}
}
