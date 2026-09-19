package forge.halcyon;

import org.testng.Assert;
import org.testng.annotations.Test;

@Test(groups = {"UnitTest"})
public class HalcyonRepoUrlTest {
    @Test
    public void resolvesShortGithubForm() {
        Assert.assertEquals(HalcyonRepoUrl.resolveBase("Eradev/forge-custom-sets-compendium"),
                "https://raw.githubusercontent.com/Eradev/forge-custom-sets-compendium/main/");
    }

    @Test
    public void resolvesShortGithubFormWithBranch() {
        Assert.assertEquals(HalcyonRepoUrl.resolveBase("Eradev/repo@develop"),
                "https://raw.githubusercontent.com/Eradev/repo/develop/");
    }

    @Test
    public void resolvesGithubTreeUrl() {
        Assert.assertEquals(HalcyonRepoUrl.resolveBase("https://github.com/Eradev/repo/tree/feature-x"),
                "https://raw.githubusercontent.com/Eradev/repo/feature-x/");
    }

    @Test
    public void resolvesPlainBaseUrl() {
        Assert.assertEquals(HalcyonRepoUrl.resolveBase("https://example.com/sets"),
                "https://example.com/sets/");
    }

    @Test
    public void stripsManifestSuffix() {
        Assert.assertEquals(HalcyonRepoUrl.resolveBase("https://example.com/sets/manifest.json"),
                "https://example.com/sets/");
    }

    @Test
    public void resolvesPackageUrl() {
        String base = "https://example.com/sets/";
        Assert.assertEquals(HalcyonRepoUrl.resolvePackageUrl(base, "packages/A.forgepkg.zip"),
                "https://example.com/sets/packages/A.forgepkg.zip");
        Assert.assertEquals(HalcyonRepoUrl.resolvePackageUrl(base, "https://cdn.example.com/A.forgepkg.zip"),
                "https://cdn.example.com/A.forgepkg.zip");
    }

    @Test
    public void formatDisplayDateUsesUtcDate() {
        Assert.assertEquals(HalcyonCatalog.formatDisplayDate("2026-09-18T12:00:00Z"), "2026-09-18");
    }

    @Test
    public void compareLastModifiedDetectsUpdate() {
        Assert.assertTrue(HalcyonCatalog.compareLastModified("2026-09-19T00:00:00Z", "2026-09-18T00:00:00Z") > 0);
    }
}
