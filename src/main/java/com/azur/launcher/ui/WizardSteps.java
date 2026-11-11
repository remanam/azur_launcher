package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

/** Step counter of the first-run wizard: the last screen always reads «Step N of N». */
final class WizardSteps {

    /** Screens of the wizard: 1 = account, 2 = install and play (one flow, the bottom button). */
    static final int TOTAL = 2;

    private WizardSteps() {
    }

    /** «Шаг n из TOTAL», n clamped to 1..TOTAL. */
    static String label(int n) {
        return I18n.tr("wizard.step", Math.max(1, Math.min(TOTAL, n)), TOTAL);
    }
}
