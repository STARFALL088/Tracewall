package tracewall.child.ui;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class TrendViewTest {
    @Test public void completeStackDeterminesScale() {
        TrendView chart = new TrendView(RuntimeEnvironment.getApplication(), null);
        chart.setSeries(new int[] {4, 10}, new int[] {8, 10});
        assertEquals(20, (int) ReflectionHelpers.getField(chart, "max"));
    }

    @Test public void unequalAndEmptySeriesKeepASafeScale() {
        TrendView chart = new TrendView(RuntimeEnvironment.getApplication(), null);
        chart.setSeries(new int[] {3}, new int[] {2, 9});
        assertEquals(9, (int) ReflectionHelpers.getField(chart, "max"));
        chart.setSeries(null, null);
        assertEquals(1, (int) ReflectionHelpers.getField(chart, "max"));
    }
}
