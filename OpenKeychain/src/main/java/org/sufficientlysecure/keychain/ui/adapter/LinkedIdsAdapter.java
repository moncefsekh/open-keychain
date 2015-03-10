/*
 * Copyright (C) 2014-2015 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2015 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.ui.adapter;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.support.v4.content.CursorLoader;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.pgp.linked.LinkedIdentity;
import org.sufficientlysecure.keychain.pgp.linked.RawLinkedIdentity;
import org.sufficientlysecure.keychain.provider.KeychainContract.Certs;
import org.sufficientlysecure.keychain.provider.KeychainContract.UserPackets;
import org.sufficientlysecure.keychain.ui.linked.LinkedIdViewFragment;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils;
import org.sufficientlysecure.keychain.ui.util.KeyFormattingUtils.State;
import org.sufficientlysecure.keychain.util.FilterCursorWrapper;

import java.io.IOException;
import java.util.WeakHashMap;


public class LinkedIdsAdapter extends UserAttributesAdapter {
    private final boolean mShowCertification;
    protected LayoutInflater mInflater;
    WeakHashMap<Integer,RawLinkedIdentity> mLinkedIdentityCache = new WeakHashMap<>();

    private Cursor mUnfilteredCursor;

    private TextView mExpander;

    public LinkedIdsAdapter(Context context, Cursor c, int flags,
            boolean showCertification, TextView expander) {
        super(context, c, flags);
        mInflater = LayoutInflater.from(context);
        mShowCertification = showCertification;

        if (expander != null) {
            mExpander = expander;
            mExpander.setVisibility(View.GONE);
            mExpander.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    showUnfiltered();
                }
            });
        }
    }

    @Override
    public Cursor swapCursor(Cursor cursor) {
        if (cursor == null) {
            mUnfilteredCursor = null;
            return super.swapCursor(null);
        }
        mUnfilteredCursor = cursor;
        FilterCursorWrapper filteredCursor = new FilterCursorWrapper(cursor) {
            @Override
            public boolean isVisible(Cursor cursor) {
                RawLinkedIdentity id = getItemAtPosition(cursor);
                return id instanceof LinkedIdentity;
            }
        };

        if (mExpander != null) {
            int hidden = filteredCursor.getHiddenCount();
            if (hidden == 0) {
                mExpander.setVisibility(View.GONE);
            } else {
                mExpander.setVisibility(View.VISIBLE);
                mExpander.setText(mContext.getResources().getQuantityString(
                        R.plurals.linked_id_expand, hidden));
            }
        }

        return super.swapCursor(filteredCursor);
    }

    private void showUnfiltered() {
        mExpander.setVisibility(View.GONE);
        super.swapCursor(mUnfilteredCursor);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {

        ViewHolder holder = (ViewHolder) view.getTag();

        if (mShowCertification) {
            holder.vVerified.setVisibility(View.VISIBLE);
            int isVerified = cursor.getInt(INDEX_VERIFIED);
            switch (isVerified) {
                case Certs.VERIFIED_SECRET:
                    KeyFormattingUtils.setStatusImage(mContext, holder.vVerified,
                            null, State.VERIFIED, KeyFormattingUtils.DEFAULT_COLOR);
                    break;
                case Certs.VERIFIED_SELF:
                    KeyFormattingUtils.setStatusImage(mContext, holder.vVerified,
                            null, State.UNVERIFIED, KeyFormattingUtils.DEFAULT_COLOR);
                    break;
                default:
                    KeyFormattingUtils.setStatusImage(mContext, holder.vVerified,
                            null, State.INVALID, KeyFormattingUtils.DEFAULT_COLOR);
                    break;
            }
        } else {
            holder.vVerified.setVisibility(View.GONE);
        }

        RawLinkedIdentity id = getItemAtPosition(cursor);
        holder.setData(mContext, id);

    }

    public RawLinkedIdentity getItemAtPosition(Cursor cursor) {
        int rank = cursor.getInt(INDEX_RANK);
        Log.d(Constants.TAG, "requested rank: " + rank);

        RawLinkedIdentity ret = mLinkedIdentityCache.get(rank);
        if (ret != null) {
            Log.d(Constants.TAG, "cached!");
            return ret;
        }
        Log.d(Constants.TAG, "not cached!");

        try {
            byte[] data = cursor.getBlob(INDEX_ATTRIBUTE_DATA);
            ret = LinkedIdentity.fromAttributeData(data);
            mLinkedIdentityCache.put(rank, ret);
            return ret;
        } catch (IOException e) {
            Log.e(Constants.TAG, "could not read linked identity subpacket data", e);
            return null;
        }
    }

    @Override
    public RawLinkedIdentity getItem(int position) {
        Cursor cursor = getCursor();
        cursor.moveToPosition(position);
        return getItemAtPosition(cursor);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        View v = mInflater.inflate(R.layout.linked_id_item, null);
        ViewHolder holder = new ViewHolder(v);
        v.setTag(holder);
        return v;
    }

    // don't show revoked user ids, irrelevant for average users
    public static final String LINKED_IDS_WHERE = UserPackets.IS_REVOKED + " = 0";

    public static CursorLoader createLoader(Activity activity, Uri dataUri) {
        Uri baseUri = UserPackets.buildLinkedIdsUri(dataUri);
        return new CursorLoader(activity, baseUri,
                UserIdsAdapter.USER_PACKETS_PROJECTION, LINKED_IDS_WHERE, null, null);
    }

    public LinkedIdViewFragment getLinkedIdFragment(Uri baseUri,
            int position, byte[] fingerprint) throws IOException {
        Cursor c = getCursor();
        c.moveToPosition(position);
        int rank = c.getInt(UserIdsAdapter.INDEX_RANK);

        Uri dataUri = UserPackets.buildLinkedIdsUri(baseUri);
        return LinkedIdViewFragment.newInstance(dataUri, rank, mShowCertification, fingerprint);
    }

    public static class ViewHolder {
        final public ImageView vVerified;
        final public ImageView vIcon;
        final public TextView vTitle;
        final public TextView vComment;

        public ViewHolder(View view) {
            vVerified = (ImageView) view.findViewById(R.id.user_id_item_certified);
            vIcon = (ImageView) view.findViewById(R.id.linked_id_type_icon);
            vTitle = (TextView) view.findViewById(R.id.linked_id_title);
            vComment = (TextView) view.findViewById(R.id.linked_id_comment);
        }

        public void setData(Context context, RawLinkedIdentity id) {

            vTitle.setText(id.getDisplayTitle(context));

            String comment = id.getDisplayComment(context);
            if (comment != null) {
                vComment.setVisibility(View.VISIBLE);
                vComment.setText(comment);
            } else {
                vComment.setVisibility(View.GONE);
            }

            vIcon.setImageResource(id.getDisplayIcon());

        }
    }

}
