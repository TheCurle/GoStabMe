package uk.gemwire.gostabme;

import uk.gemwire.gostabme.IStabbed;

public class StabbedProvider implements IStabbed {

    private boolean isStabbed;

    @Override
    public boolean isStabbed() {
        return isStabbed;
    }

    @Override
    public void setStabbed(boolean stabbed) {
        isStabbed = stabbed;
    }
}
