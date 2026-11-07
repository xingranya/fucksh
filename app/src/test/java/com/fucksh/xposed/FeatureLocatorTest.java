package com.fucksh.xposed;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 验证动态特征点定义与配置快照逻辑。
 */
public class FeatureLocatorTest {

    @Test
    public void testClassNameConversion() {
        Assert.assertEquals("java.lang.String", FeatureLocator.className("Ljava/lang/String;"));
        Assert.assertEquals("com.mobiletools.systemhelper.control.DMApplication",
                FeatureLocator.className("Lcom/mobiletools/systemhelper/control/DMApplication;"));
        Assert.assertEquals("e1.b", FeatureLocator.className("Le1/b;"));
        Assert.assertEquals("g1.n", FeatureLocator.className("Lg1/n;"));
    }

    @Test
    public void testMethodRefDescribe() {
        FeatureLocator.MethodRef ref = new FeatureLocator.MethodRef(
                "Le1/b;", "b", Collections.emptyList(), "Ljava/lang/String;");
        Assert.assertEquals("e1.b#b()", ref.describe());

        FeatureLocator.MethodRef withArgs = new FeatureLocator.MethodRef(
                "Lg1/f;", "f", Arrays.asList("Ljava/lang/String;", "[B"), "Z");
        Assert.assertEquals("g1.f#f(String,[B)", withArgs.describe());
    }

    @Test
    public void testHookConfigSnapshotFallback() {
        HookConfig.Snapshot fallback = HookConfig.Snapshot.fallback();
        Assert.assertTrue(fallback.guard);
        Assert.assertTrue(fallback.egress);
        Assert.assertTrue(fallback.collect);
        Assert.assertTrue(fallback.location);
        Assert.assertTrue(fallback.db);
        Assert.assertTrue(fallback.wipe);
        Assert.assertTrue(fallback.analytics);
        Assert.assertFalse(fallback.managerActive);
        Assert.assertEquals(0L, fallback.syncRequestTime);
        Assert.assertEquals(0L, fallback.configTimestamp);
    }

    @Test
    public void testHookConfigSnapshotEquals() {
        HookConfig.Snapshot s1 = HookConfig.Snapshot.fallback();
        HookConfig.Snapshot s2 = HookConfig.Snapshot.fallback();
        Assert.assertEquals(s1, s2);
        Assert.assertEquals(s1.hashCode(), s2.hashCode());
    }
}
