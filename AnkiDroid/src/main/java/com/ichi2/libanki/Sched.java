/****************************************************************************************
 * Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>                         *
 * Copyright (c) 2012 Kostas Spyropoulos <inigo.aldana@gmail.com>                       *
 * Copyright (c) 2013 Houssam Salem <houssam.salem.au@gmail.com>                        *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General private License as published by the Free Software       *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General private License for more details.            *
 *                                                                                      *
 * You should have received a copy of the GNU General private License along with        *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.libanki;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteConstraintException;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;

import com.ichi2.anki.R;
import com.ichi2.libanki.hooks.Hooks;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Random;

import timber.log.Timber;

public class Sched {



    // Not in libanki
    private static final int[] FACTOR_ADDITION_VALUES = { -150, 0, 150 };

    private String mName = "std";
    // 有自定义学习吗？
    private boolean mHaveCustomStudy = true;
    // todo_john 不理解，mSpreadRev
    private boolean mSpreadRev = true;
    // todo_john 回答完这个问题后要不要搁置这个卡片的姊妹卡片？
    private boolean mBurySiblingsOnAnswer = true;

    private Collection mCol;
    // 队列限制；
    private int mQueueLimit;
    // 报告限制
    private int mReportLimit;
    //  mReps默认是0，每次取出一张卡片，它就会累加1；
    private int mReps;
    // 有队列吗？
    private boolean mHaveQueues;
    // 今天
    private int mToday;
    // 今天的截至时间；单位是秒，它总是指向某一天的凌晨四点钟
    public long mDayCutoff;

    //新的，学习中的，复习的 分别的总数量；
    private int mNewCount;
    private int mLrnCount;
    private int mRevCount;

    //todo_john modulus 新卡的系数？不懂
    private int mNewCardModulus;

    //todo_john 估计缓存？
    private double[] mEtaCache = new double[] { -1, -1, -1, -1 };

    // Queues队列
    // 新卡队列
    private final LinkedList<Long> mNewQueue = new LinkedList<Long>();
    // 学习卡队列，这个队列只包含当天学习的队列
    private final LinkedList<long[]> mLrnQueue = new LinkedList<long[]>();
    // 学习队列，这个队列包含夸天学习的队列；
    private final LinkedList<Long> mLrnDayQueue = new LinkedList<Long>();
    // 复习队列；
    private final LinkedList<Long> mRevQueue = new LinkedList<Long>();

    //新的， 学习中的，复习的卡片，分别对应的牌组的集合；
    private LinkedList<Long> mNewDids;
    private LinkedList<Long> mLrnDids;
    private LinkedList<Long> mRevDids;

    // Not in libanki todo_john 一个antivity的弱应用，用意为何呢?
    private WeakReference<Activity> mContextReference;

    /**
     * queue types: 0=new/cram, 1=lrn, 2=rev, 3=day lrn, -1=suspended, -2=buried
     * revlog types: 0=lrn, 1=rev, 2=relrn, 3=cram
     * positive revlog intervals are in days (rev), negative in seconds (lrn)
     */

    public Sched(Collection col) {
        mCol = col;
        mQueueLimit = 50;
        mReportLimit = 1000;
        mReps = 0;
        mHaveQueues = false;
        _updateCutoff();
    }


    /**
     * Pop the next card from the queue. None if finished.
     * 从队列中取出下一个卡片，
     */
    public Card getCard() {
        _checkDay();
        if (!mHaveQueues) {
            reset();
        }
        Card card = _getCard();
        if (card != null) {
            mCol.log(card);
            if (!mBurySiblingsOnAnswer) {
                _burySiblings(card);
            }
            mReps += 1;
            card.startTimer();
            return card;
        }
        return null;
    }

    // 重新设置
    public void reset() {
        _updateCutoff();
        _resetLrn();// 重新设置今天的卡片的数量，清空学习队列，设置学习牌组为当前活动牌组
        _resetRev();// 重新设置今天的复习卡片的数量，清空复习队列，
        _resetNew();// 设置新卡片系数，重新设置今天的新卡片的数量，清空新卡片的队列；
        mHaveQueues = true;
    }

    // 回答卡片
    public void answerCard(Card card, int ease) {
        mCol.log();
        mCol.markReview(card); // 对当前卡片mark，即标记学习过的卡片，用来做取消操作，
        if (mBurySiblingsOnAnswer) {
            _burySiblings(card); // 搁置这张卡片的姊妹卡片；
        }
        card.setReps(card.getReps() + 1); //当前的卡片重复次数累加1；
        // former is for logging new cards, latter also covers filt. decks
        card.setWasNew((card.getType() == 0)); //这张卡片是新卡吗?
        boolean wasNewQ = (card.getQueue() == 0); //这张卡片是在新卡队列吗？
        if (wasNewQ) {
            // came from the new queue, move to learning
            card.setQueue(1); // 将这张卡的队列变成学习队列；
            // if it was a new card, it's now a learning card
            if (card.getType() == 0) {
                card.setType(1); // 如果是新卡，则将它变成学习中的卡
            }
            // init reps to graduation //_startingLeft(card)返回开始剩余次数，比如返回1002，总共还要学习2次，但今天只能在学习一次，
            card.setLeft(_startingLeft(card)); //只要来自新卡队列，都先设置left， 通过left可以知道此卡来自于来自于全新，还是过滤；
            // dynamic?如果是过滤的卡片，并且卡片类型是复习的卡片，这种卡片进入新卡队列怎么办呢？
            if (card.getODid() != 0 && card.getType() == 2) {
                // 这种情况说明此卡片，来自于过滤牌组，
                if (_resched(card)) {
                    // 看一下，此过滤卡片的牌组是否声明重新安排学习计划，如果是，则往下走
                    // reviews get their ivl boosted on first sight
                    // // _dynIvlBoost(card) // 为动态卡片返回一个ivl，它会考虑上一次的ivl,最后一次看完距离现在的时间，以及卡片因子来计算一个合适的ivl返回；
                    card.setIvl(_dynIvlBoost(card));
                    // 为卡片设置一个odue，用于卡片返回到原始牌组中的时候继续学习使用，
                    card.setODue(mToday + card.getIvl());
                }
            }
            // 如果是来自全新卡片，则card设置：queue, type, left;
            // 如果来自失误卡片，则card设置： ivl, odue;
            _updateStats(card, "new"); // 对当前卡所在的牌组的dconf的newToday中的第二个字段累加1；
            // 所谓的新卡片类型，新卡片队列，在他出现那一瞬间，当你给他选择下次出现时间那一刻，无论你选择哪个按钮，它都已变成学习中的卡片，并进入学习队列。
        }
        if (card.getQueue() == 1 || card.getQueue() == 3) {
            _answerLrnCard(card, ease);
            if (!wasNewQ) {
                _updateStats(card, "lrn");
            }
        } else if (card.getQueue() == 2) {
            _answerRevCard(card, ease);
            _updateStats(card, "rev");
        } else {
            throw new RuntimeException("Invalid queue");
        }
        _updateStats(card, "time", card.timeTaken());
        card.setMod(Utils.intNow());
        card.setUsn(mCol.usn());
        card.flushSched();
    }


    public int[] counts() {
        return counts(null);
    }

    // 给你一个卡片，你看吧，我该怎么累加，你说了算，
    public int[] counts(Card card) {
        int[] counts = {mNewCount, mLrnCount, mRevCount};
        if (card != null) {
            int idx = countIdx(card);
            if (idx == 1) {
                counts[1] += card.getLeft() / 1000;
            } else {
                counts[idx] += 1;
            }
        }
        return counts;
    }


    /**
     * Return counts over next DAYS. Includes today.
     */
    public int dueForecast() {
        return dueForecast(7);
    }


    public int dueForecast(int days) {
        // TODO:...
        return 0;
    }

    // 放回当前卡片的队列索引号；
    public int countIdx(Card card) {
        if (card.getQueue() == 3) {
            return 1;
        }
        return card.getQueue();
    }

    // 回答按钮个数；
    public int answerButtons(Card card) {
        if (card.getODue() != 0) {
            // normal review in dyn deck? 如果是过滤卡片，或是过失卡片；
            if (card.getODid() != 0 && card.getQueue() == 2) {
                // 如果处在过滤的牌组中，并且在复习队列，就返回4；
                return 4;
            }
            JSONObject conf = _lrnConf(card);
            try {
                if (card.getType() == 0 || card.getType() == 1 || conf.getJSONArray("delays").length() > 1) {
                    return 3;
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            return 2;
        } else if (card.getQueue() == 2) {
            return 4;
        } else {
            return 3;
        }
    }


    /*
     * Unbury cards.取消搁置卡片
     */
    public void unburyCards() {
        try {
            mCol.getConf().put("lastUnburied", mToday);
            mCol.log(mCol.getDb().queryColumn(Long.class, "select id from cards where queue = -2", 0));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        // 把那些被搁置的卡片的队列值修改，修改成与这个卡片的type一致；
        mCol.getDb().execute("update cards set queue=type where queue = -2");
    }

    // 针对牌组集合中正在活动的牌组，取消搁置这些牌组中之前被搁置的卡片，
    public void unburyCardsForDeck() {
        // sids 是当前正活动的所有牌组的id连成的字符串
        String sids = Utils.ids2str(mCol.getDecks().active());
        mCol.log(mCol.getDb().queryColumn(Long.class, "select id from cards where queue = -2 and did in " + sids, 0));
        mCol.getDb().execute("update cards set mod=?,usn=?,queue=type where queue = -2 and did in " + sids,
                new Object[]{Utils.intNow(), mCol.usn()});
    }


    /**
     * Rev/lrn/time daily stats *************************************************
     * **********************************************
     */
    // 更新统计
    private void _updateStats(Card card, String type) {
        _updateStats(card, type, 1);
    }

    /**
     * 把整张卡所在的牌组，以及这个牌组的所有父牌组的xxxxToday的第二个元素的值都累加1；
     * @param card
     * @param type 可能是new, time, lrn, rev
     * @param cnt
     */
    public void _updateStats(Card card, String type, long cnt) {
        // 可能是newToday, timeToday, lrnToday, revToday
        String key = type + "Today";
        long did = card.getDid();
        /** parents(did) 这个方法返回的是did的所有父牌组的具体描述；
         * 如果did的名字是 aa::bb::cc::dd
         * 则返回的是{aa, aa::bb, aa::bb::cc}这三个牌组对应的具体描述数据；
         */
        List<JSONObject> list = mCol.getDecks().parents(did);
        list.add(mCol.getDecks().get(did));
        for (JSONObject g : list) {
            try {
                JSONArray a = g.getJSONArray(key);
                // 假设a的值是{0：183， 1：0}
                a.put(1, a.getLong(1) + cnt);
                // 则现在a的值是：{0：183， 1：1}
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            mCol.getDecks().save(g);
        }
    }

    // 扩展限制；
    public void extendLimits(int newc, int rev) {
        // 获取当前牌组；
        JSONObject cur = mCol.getDecks().current();
        ArrayList<JSONObject> decks = new ArrayList<JSONObject>();
        decks.add(cur);
        try {
            // 获取当前牌组的父牌组，并添加到decks中；
            decks.addAll(mCol.getDecks().parents(cur.getLong("id")));
            for (long did : mCol.getDecks().children(cur.getLong("id")).values()) {
                // 获取当前牌组的子牌组，并加入集合中；
                decks.add(mCol.getDecks().get(did));
            }
            for (JSONObject g : decks) {
                // add
                JSONArray ja = g.getJSONArray("newToday");
                // ja.getInt(1)代表着今天已经学习的卡片的个数；或是复习的卡片的个数；
                ja.put(1, ja.getInt(1) - newc);
                g.put("newToday", ja);
                ja = g.getJSONArray("revToday");
                ja.put(1, ja.getInt(1) - rev);
                g.put("revToday", ja);
                mCol.getDecks().save(g);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 走一步说一步，看看现在还有要学习或是复习多少张卡片，limFn计算出距离上限还要学习多少张；
    // cntFn会计算出，实际还要学吸多少张卡片，
    private int _walkingCount(Method limFn, Method cntFn) {
        int tot = 0;
        HashMap<Long, Integer> pcounts = new HashMap<Long, Integer>();
        // for each of the active decks
        try {
            // 遍历所有活动的牌组
            for (long did : mCol.getDecks().active()) {
                // get the individual deck's limit 获取单个的牌组的dconf中的每日的复习数量的限制
                int lim = (Integer)limFn.invoke(Sched.this, mCol.getDecks().get(did));
                if (lim == 0) {
                    continue;
                }
                // check the parents 检查第一个活动的牌组的所有父牌组
                List<JSONObject> parents = mCol.getDecks().parents(did);
                // 遍历第一个活动牌组的所有父牌组，
                for (JSONObject p : parents) {
                    // add if missing
                    long id = p.getLong("id");
                    if (!pcounts.containsKey(id)) {
                        // pcounts的元素将是{id, count}即存放，每个父牌组的id,和这个父牌组今天按照dconf配置，还需要学习的复习卡片还有多少个，
                        pcounts.put(id, (Integer)limFn.invoke(Sched.this, p));
                    }
                    // take minimum of child and parent 即，**********子牌组的复习的卡片的限制一定要小于父牌组的复习卡片的限制，
                    lim = Math.min(pcounts.get(id), lim);
                }
                // see how many cards we actually have，看看当前的牌组中实际还要学习的复习卡片数量
                int cnt = (Integer)cntFn.invoke(Sched.this, did, lim);
                // if non-zero, decrement from parents counts
                // todo_john 最终返回的只跟cnt有关系，为什么还要管什么pcount呢？这是个循环，它，还要循环回去下次使用；
                for (JSONObject p : parents) {
                    long id = p.getLong("id");
                    pcounts.put(id, pcounts.get(id) - cnt);
                }
                // we may also be a parent
                pcounts.put(did, lim - cnt);
                // and add to running total
                tot += cnt;
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return tot;
    }


    /**
     * Deck list **************************************************************** *******************************
     */


    /**
     * Returns [deckname, did, rev, lrn, new]
     * 返回牌组列表所需要的数据；
     */
    public List<DeckDueTreeNode> deckDueList() {
        _checkDay(); // 看看需不需要reset；
        // 覆盖孤儿卡，即它的牌组不在了，但是卡片还在，这时候，就把它的牌组id设置为1；即放入默认牌组；
        mCol.getDecks().recoverOrphans();
        ArrayList<JSONObject> decks = mCol.getDecks().allSorted();
        // lims放置那些牌组已经被计算过这些数值了；{deck.getString("name"), new Integer[]{nlim, rlim}};
        HashMap<String, Integer[]> lims = new HashMap<String, Integer[]>();
        ArrayList<DeckDueTreeNode> data = new ArrayList<DeckDueTreeNode>();
        try {
            for (JSONObject deck : decks) {
                // if we've already seen the exact same deck name, remove the
                // invalid duplicate and reload
                if (lims.containsKey(deck.getString("name"))) {
                    // 如果有重复的牌组名出现，则删除这个牌组，这个方法执行完毕，
                    // 删除这个牌组deck，是否将它的卡片都删掉，是否将它的子牌组都删掉；
                    mCol.getDecks().rem(deck.getLong("id"), false, true);
                    return deckDueList();
                }
                // 获取给的牌组deck的父牌组的名字，放入p中；
                String p;
                List<String> parts = Arrays.asList(deck.getString("name").split("::", -1));
                if (parts.size() < 2) {
                    p = null;
                } else {
                    parts = parts.subList(0, parts.size() - 1);
                    p = TextUtils.join("::", parts);
                }
                // new 获取今天还有几个新卡片需要学习，这个数字放到nlim中；
                int nlim = _deckNewLimitSingle(deck);
                if (!TextUtils.isEmpty(p)) {
                    if (!lims.containsKey(p)) {
                        // if parent was missing, this deck is invalid, and we need to reload the deck list
                        // 如果父牌组已经没有了，这个牌组也就无效了，我们需要重新加载牌组列表；
                        mCol.getDecks().rem(deck.getLong("id"), false, true);
                        return deckDueList();
                    }
                    // 子牌组要学习的卡片数量不能超过父牌组要学习的卡片的数量；
                    nlim = Math.min(nlim, lims.get(p)[0]);
                }
                // 返回这个牌组实际能返回的新卡片数量；
                int _new = _newForDeck(deck.getLong("id"), nlim);
                // learning
                int lrn = _lrnForDeck(deck.getLong("id"));
                // reviews
                int rlim = _deckRevLimitSingle(deck);
                if (!TextUtils.isEmpty(p)) {
                    // 子牌组要复习的卡片的数量不能大于父牌组；
                    rlim = Math.min(rlim, lims.get(p)[1]);
                }
                int rev = _revForDeck(deck.getLong("id"), rlim);
                // save to list
                data.add(new DeckDueTreeNode(deck.getString("name"), deck.getLong("id"), rev, lrn, _new));
                // add deck as a parent 遍历过的牌组加到lims里面，
                lims.put(deck.getString("name"), new Integer[]{nlim, rlim});
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return data;
    }

    // 牌组过期树状结构
    public List<DeckDueTreeNode> deckDueTree() {
        return _groupChildren(deckDueList());
    }


    private List<DeckDueTreeNode> _groupChildren(List<DeckDueTreeNode> grps) {
        // first, split the group names into components
        for (DeckDueTreeNode g : grps) {
            // 将牌组名字符串a::b::c::d 变成数组['a', 'b', 'c', 'd']
            g.names = g.names[0].split("::", -1);
        }
        // and sort based on those components
        Collections.sort(grps);
        // then run main function
        return _groupChildrenMain(grps);
    }

    //                  children = _groupChildrenMain(children);
    private List<DeckDueTreeNode> _groupChildrenMain(List<DeckDueTreeNode> grps) {
        List<DeckDueTreeNode> tree = new ArrayList<DeckDueTreeNode>();
        // group and recurse
        ListIterator<DeckDueTreeNode> it = grps.listIterator();
        while (it.hasNext()) {
            DeckDueTreeNode node = it.next();
            String head = node.names[0];
            // Compose the "tail" node list. The tail is a list of all the nodes that proceed
            // the current one that contain the same name[0]. I.e., they are subdecks that stem
            // from this node. This is our version of python's itertools.groupby.
            List<DeckDueTreeNode> tail  = new ArrayList<DeckDueTreeNode>();
            tail.add(node);
            while (it.hasNext()) {
                DeckDueTreeNode next = it.next();
                if (head.equals(next.names[0])) {
                    // Same head - add to tail of current head.
                    tail.add(next);
                } else {
                    // We've iterated past this head, so step back in order to use this node as the
                    // head in the next iteration of the outer loop.
                    // 返回迭代器中的上一个元素；
                    it.previous();
                    break;
                }
            }
            /**
             * 最后tail变成：
             * ['a']                  , 0x23a3, 50, 5, 50,
             * ['a', 'b']             , 0x23a4, 50, 5, 50,
             * ['a', 'b', 'c']        , 0x23a5, 50, 5, 50,
             * ['a', 'b', 'c', 'd']   , 0x23ae, 50, 5, 50,
             */
            Long did = null;
            int rev = 0;
            int _new = 0;
            int lrn = 0;
            List<DeckDueTreeNode> children = new ArrayList<DeckDueTreeNode>();
            for (DeckDueTreeNode c : tail) {
                if (c.names.length == 1) {
                    // current node
                    did = c.did;
                    rev += c.revCount;
                    lrn += c.lrnCount;
                    _new += c.newCount;
                } else {
                    // set new string to tail
                    // 对于c.names从['a' ,'b', 'c', 'd'] 变成 ['b', 'c', 'd']
                    String[] newTail = new String[c.names.length-1];
                    System.arraycopy(c.names, 1, newTail, 0, c.names.length-1);
                    c.names = newTail;
                    children.add(c);
                }
            }
            /**
             * 最后childrens变成：
             * ['a', 'b']             , 0x23a4, 50, 5, 50,
             * ['a', 'b', 'c']        , 0x23a5, 50, 5, 50,
             * ['a', 'b', 'c', 'd']   , 0x23ae, 50, 5, 50,
             */
            children = _groupChildrenMain(children);
            // tally up children counts
            for (DeckDueTreeNode ch : children) {
                rev +=  ch.revCount;
                lrn +=  ch.lrnCount;
                _new += ch.newCount;
            }
            // limit the counts to the deck's limits
            JSONObject conf = mCol.getDecks().confForDid(did);
            JSONObject deck = mCol.getDecks().get(did);
            try {
                if (conf.getInt("dyn") == 0) {
                    rev = Math.max(0, Math.min(rev, conf.getJSONObject("rev").getInt("perDay") - deck.getJSONArray("revToday").getInt(1)));
                    _new = Math.max(0, Math.min(_new, conf.getJSONObject("new").getInt("perDay") - deck.getJSONArray("newToday").getInt(1)));
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }

            tree.size();
            tree.size();
            tree.add(new DeckDueTreeNode(head, did, rev, lrn, _new, children));
        }
        tree.size();
        tree.size();
        return tree;
    }


    /**
     * Getting the next card ****************************************************
     * *******************************************
     */

    /**
     * Return the next due card, or null.
     */
    private Card _getCard() {
        // learning card due?
        Card c = _getLrnCard(); //它会判断此刻有没有学习的卡片到期，如果有，则蹦出一个学习的卡片
        if (c != null) {
            return c;
        }
        // new first, or time for one?
        // _timeForNewCard()此刻新卡片是否该展现出来呢？它取决于现在已经取出了多少张卡片，和新卡片系数；
        if (_timeForNewCard()) {
            c = _getNewCard();
            if (c != null) {
                return c;
            }
        }
        // Card due for review?
        c = _getRevCard(); //直接从复习的卡片队列中取出一张卡片，拿来就用，
        if (c != null) {
            return c;
        }
        // day learning card due?
        c = _getLrnDayCard(); // 直接从天学习队列中取出一张卡片，拿来就用，
        if (c != null) {
            return c;
        }
        // New cards left?
        c = _getNewCard();  // 直接去除新卡片吧，不用做任何判断
        if (c != null) {
            return c;
        }
        // collapse or finish
        return _getLrnCard(true);
    }


    /**
     * New cards **************************************************************** *******************************
     * 遍历所有的活动牌组，计算今天要学习的新卡片的总数量；
     */

    private void _resetNewCount() {
        try {
            mNewCount = _walkingCount(Sched.class.getDeclaredMethod("_deckNewLimitSingle", JSONObject.class),
                    Sched.class.getDeclaredMethod("_cntFnNew", long.class, int.class));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }


    // Used as an argument for _walkingCount() in _resetNewCount() above
    // 这个牌组今天实际上还有多少新卡片需要学习
    @SuppressWarnings("unused")
    private int _cntFnNew(long did, int lim) {
        return mCol.getDb().queryScalar(
                "SELECT count() FROM (SELECT 1 FROM cards WHERE did = " + did + " AND queue = 0 LIMIT " + lim + ")");
    }


    private void _resetNew() {
        _resetNewCount(); //遍历所有的活动牌组，计算今天要学习的新卡片的总数量
        mNewDids = new LinkedList<Long>(mCol.getDecks().active());
        mNewQueue.clear(); // 清空新卡片队列
        _updateNewCardRatio(); //更新新卡片系数；
    }


    private boolean _fillNew() {
        if (mNewQueue.size() > 0) {
            return true;
        }
        if (mNewCount == 0) {
            return false;
        }
        while (!mNewDids.isEmpty()) {
            long did = mNewDids.getFirst();
            int lim = Math.min(mQueueLimit, _deckNewLimit(did));
            Cursor cur = null;
            if (lim != 0) {
                mNewQueue.clear();
                try {
                    // fill the queue with the current did
                    cur = mCol
                            .getDb()
                            .getDatabase()
                            .rawQuery("SELECT id FROM cards WHERE did = " + did + " AND queue = 0 order by due LIMIT " + lim,
                                    null);
                    while (cur.moveToNext()) {
                        mNewQueue.add(cur.getLong(0));
                    }
                } finally {
                    if (cur != null && !cur.isClosed()) {
                        cur.close();
                    }
                }
                if (!mNewQueue.isEmpty()) {
                    // Note: libanki reverses mNewQueue and returns the last element in _getNewCard().
                    // AnkiDroid differs by leaving the queue intact and returning the *first* element
                    // in _getNewCard().
                    return true;
                }
            }
            // nothing left in the deck; move to next
            mNewDids.remove();
        }
        if (mNewCount != 0) {
            // if we didn't get a card but the count is non-zero,
            // we need to check again for any cards that were
            // removed from the queue but not buried
            _resetNew();
            return _fillNew();
        }
        return false;
    }


    private Card _getNewCard() {
        if (_fillNew()) {
            mNewCount -= 1;
            return mCol.getCard(mNewQueue.remove());
        }
        return null;
    }

    // 计算新卡片系数，当新卡被混合进复习的卡片进行学习时候，将返回一个计算过的值；
    // 当新卡没有混合到复习的卡片中的时候，新卡片系数为0;
    private void _updateNewCardRatio() {
        try {
            // Consts.NEW_CARDS_DISTRIBUTE 指定新卡应该被分布在复习的卡片之间，和复习的卡片混合出现；
            if (mCol.getConf().getInt("newSpread") == Consts.NEW_CARDS_DISTRIBUTE) {
                if (mNewCount != 0) {
                    // 新卡片的系数=新卡片与复习卡片的综合除以新卡片的数量，
                    mNewCardModulus = (mNewCount + mRevCount) / mNewCount;
                    // if there are cards to review, ensure modulo >= 2
                    if (mRevCount != 0) {
                        //如果复习的卡片数量不等于0，则要保证新卡片的系数大于等于2；
                        mNewCardModulus = Math.max(2, mNewCardModulus);
                    }
                    return;
                }
            }
            // 如果新卡片与复习的卡片不混排，则新卡系数等于0；
            mNewCardModulus = 0;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * @return True if it's time to display a new card when distributing.
     * 此刻新卡片是否该展现出来呢？它取决于现在已经取出了多少张卡片，和新卡片系数；
     */
    private boolean _timeForNewCard() {
        if (mNewCount == 0) {
            return false;
        }
        int spread;
        try {
            spread = mCol.getConf().getInt("newSpread");
            // spread表示，新卡片是否与复习的卡片混编队列，或是怎么样！
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        if (spread == Consts.NEW_CARDS_LAST) {
            return false;
        } else if (spread == Consts.NEW_CARDS_FIRST) {
            return true;
        } else if (mNewCardModulus != 0) {
            // mReps在每次初始化的时候都为0，然后蹦出一张卡片，这个值就累加1
            return (mReps != 0 && (mReps % mNewCardModulus == 0));
        } else {
            return false;
        }
    }


    private int _deckNewLimit(long did) {
        return _deckNewLimit(did, null);
    }


    private int _deckNewLimit(long did, Method fn) {
        try {
            if (fn == null) {
                fn = Sched.class.getDeclaredMethod("_deckNewLimitSingle", JSONObject.class);
            }
            List<JSONObject> decks = mCol.getDecks().parents(did);
            decks.add(mCol.getDecks().get(did));
            int lim = -1;
            // for the deck and each of its parents
            int rem = 0;
            for (JSONObject g : decks) {
                rem = (Integer) fn.invoke(Sched.this, g);
                if (lim == -1) {
                    lim = rem;
                } else {
                    lim = Math.min(rem, lim);
                }
            }
            return lim;
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }


    /* New count for a single deck. */
    // 这个牌组实际能返回的新卡片数量；
    public int _newForDeck(long did, int lim) {
    	if (lim == 0) {
    		return 0;
    	}
    	lim = Math.min(lim, mReportLimit);
    	return mCol.getDb().queryScalar("SELECT count() FROM (SELECT 1 FROM cards WHERE did = " + did + " AND queue = 0 LIMIT " + lim + ")");
    }


    /* Limit for deck without parent limits.今天还有几张新卡片需要学习 */
    public int _deckNewLimitSingle(JSONObject g) {
        try {
            if (g.getInt("dyn") != 0) {
                return mReportLimit;
            }
            JSONObject c = mCol.getDecks().confForDid(g.getLong("id"));
            return Math.max(0, c.getJSONObject("new").getInt("perDay") - g.getJSONArray("newToday").getInt(1));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public int totalNewForCurrentDeck() {
        return mCol.getDb().queryScalar("SELECT count() FROM cards WHERE id IN (SELECT id FROM cards WHERE did IN " + Utils.ids2str(mCol.getDecks().active()) + " AND queue = 0 LIMIT " + mReportLimit + ")");
    }

    /**
     * Learning queues *********************************************************** ************************************
     * // 重新设置学习数量，mDayCutoff今天的截至时间，它总是只想某一天的凌晨四点整那个时刻；
     */

    private void _resetLrnCount() {
        // sub-day
        mLrnCount = mCol.getDb().queryScalar(
                // _deckLimit()获得当前活动的牌组，以下sql语句将返回今天到期的，学习中的卡片，限制在1000张，然后根据left来累计他们总共要学习的卡片张次；
                "SELECT sum(left / 1000) FROM (SELECT left FROM cards WHERE did IN " + _deckLimit()
                + " AND queue = 1 AND due < " + mDayCutoff + " LIMIT " + mReportLimit + ")");

        // day 返回以天为单位的处于学习阶段的卡片，它在队列序号3中，
        mLrnCount += mCol.getDb().queryScalar(
                "SELECT count() FROM cards WHERE did IN " + _deckLimit() + " AND queue = 3 AND due <= " + mToday
                        + " LIMIT " + mReportLimit);
    }

    // 重新设置今天的学习队列
    private void _resetLrn() {
        _resetLrnCount(); // 重新设置学习数量；
        mLrnQueue.clear(); // 学习队列清空，
        mLrnDayQueue.clear(); // 天学习队列清空
        // 设置当前活动的牌组为学习的牌组
        mLrnDids = mCol.getDecks().active();
    }


    // sub-day learning即为;重新生成学习队列，并看看学习的队列lrn里面有没有内容呢？
    private boolean _fillLrn() {
        if (mLrnCount == 0) {
            return false;
        }
        if (!mLrnQueue.isEmpty()) {
            return true;
        }
        Cursor cur = null;
        mLrnQueue.clear(); // 先把学习队列清空
        try {
            cur = mCol
                    .getDb()
                    .getDatabase()
                    .rawQuery(
                            // _deckLimit() 获取活动的牌组，并链接成字符串，
                            "SELECT due, id FROM cards WHERE did IN " + _deckLimit() + " AND queue = 1 AND due < "
                                    + mDayCutoff + " LIMIT " + mReportLimit, null);
            while (cur.moveToNext()) {
                mLrnQueue.add(new long[] { cur.getLong(0), cur.getLong(1) });
            }
            // as it arrives sorted by did first, we need to sort it
            Collections.sort(mLrnQueue, new Comparator<long[]>() {
                @Override
                public int compare(long[] lhs, long[] rhs) {
                    return Long.valueOf(lhs[0]).compareTo(rhs[0]);
                }
            });
            return !mLrnQueue.isEmpty();
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
    }


    private Card _getLrnCard() {
        return _getLrnCard(false);
    }


    private Card _getLrnCard(boolean collapse) {
        if (_fillLrn()) {
            // _fillLrn()创建学习队列，并看看队列里面有没有内容
            double cutoff = Utils.now(); // 获得当前的秒数；
            if (collapse) {
                try {
                    cutoff += mCol.getConf().getInt("collapseTime");
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
            if (mLrnQueue.getFirst()[0] < cutoff) {
                // 如果学习列表的第一个元素，即第一个卡片的预期时间是1234秒，而现在的时间是1235秒，说明此卡片已经预期了，好了，把他弹出来吧！
                // mLrnQueue 的元素（c.due c.id）
                long id = mLrnQueue.remove()[1]; // remove()会调用removeFirstImpl()；
                Card card = mCol.getCard(id);
                mLrnCount -= card.getLeft() / 1000; //只要这个学习的卡片弹出来，学习的计数就减少tod个，
                return card;
            }
        }
        return null;
    }


    // daily learning
    private boolean _fillLrnDay() {
        if (mLrnCount == 0) {
            return false;
        }
        if (!mLrnDayQueue.isEmpty()) {
            return true;
        }
        while (mLrnDids.size() > 0) {
            long did = mLrnDids.getFirst();
            // fill the queue with the current did
            mLrnDayQueue.clear();
            Cursor cur = null;
            try {
                cur = mCol
                        .getDb()
                        .getDatabase()
                        .rawQuery(
                                "SELECT id FROM cards WHERE did = " + did + " AND queue = 3 AND due <= " + mToday
                                        + " LIMIT " + mQueueLimit, null);
                while (cur.moveToNext()) {
                    mLrnDayQueue.add(cur.getLong(0));
                }
            } finally {
                if (cur != null && !cur.isClosed()) {
                    cur.close();
                }
            }
            if (mLrnDayQueue.size() > 0) {
                // order
                Random r = new Random();
                r.setSeed(mToday);
                Collections.shuffle(mLrnDayQueue, r);
                // is the current did empty?
                if (mLrnDayQueue.size() < mQueueLimit) {
                    mLrnDids.remove();
                }
                return true;
            }
            // nothing left in the deck; move to next
            mLrnDids.remove();
        }
        return false;
    }


    private Card _getLrnDayCard() {
        if (_fillLrnDay()) {
            mLrnCount -= 1;
            return mCol.getCard(mLrnDayQueue.remove());
        }
        return null;
    }


    /**
     * 即，回答学习队列的卡片的问题，该如何处置呢？
     * 它分两种情况，一种是回答完，进入复习队列，二种是回答完还处在学习队列，三种是回答完进入lapse
     * @param ease 1=no, 2=yes, 3=remove 学习中的卡片一般都是三个选项按钮，
     * 所谓的新卡片类型，新卡片队列，在他出现那一瞬间，当你给他选择下次出现时间那一刻，无论你选择哪个按钮，它都已变成学习中的卡片，并进入学习队列。
     */
    private void _answerLrnCard(Card card, int ease) {
        JSONObject conf = _lrnConf(card); // 返回学习状态的配置信息可能是new的也可能是lapse的；
        int type; // 问一问这个学习卡片队列的卡片的出处，是来自于哪里，是来自于新卡片呢？还是复习中的卡片呢？还是来自过滤的牌组呢?
        if (card.getODid() != 0 && !card.getWasNew()) {
            type = 3; // 过滤出的牌组中的学习类型卡片和复习类型卡片
        } else if (card.getType() == 2) {
            type = 2; // 普通类型的复习类型卡片，因为lapse，而进入学习队列
        } else {
            type = 0; //有新卡片直接变成的学习中的卡片，或是本来就在新卡片的学习阶段；
        }
        // Todo_john 这里的leaving是啥意思呢？
        boolean leaving = false;
        // lrnCount was decremented once when card was fetched 当卡片被抽出，学习的次数减少一次；
        int lastLeft = card.getLeft();
        // immediate graduate? 你要立即毕业吗？
        if (ease == 3) {
            // 只有新卡片的学习才会进入这里，无论是新卡片的第一次学习，还是第二次学习，点击第三个按钮都会进入这里来
            _rescheduleAsRev(card, conf, true);
            leaving = true;
            // graduation time?
        } else if (ease == 2 && (card.getLeft() % 1000) - 1 <= 0) {
            // 如果处在学习队列中，但是剩下的学习次数小于等于1，
            // 进入这里有两种情况，一种是新卡片第二次学习，点击第二个按钮
            // 还有一种情况是，过滤卡片或失误卡片点击第二个按钮，
            _rescheduleAsRev(card, conf, false);
            leaving = true;
        } else {
            // one step towards graduation 这种情况是，回答完还进入学习队列；还要在学习一次，
            if (ease == 2) {
                // decrement real left count and recalculate left today
                int left = (card.getLeft() % 1000) - 1;
                try {
                    // 设置left，学习队列中的卡片都要设置left
                    card.setLeft(_leftToday(conf.getJSONArray("delays"), left) * 1000 + left);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                // failed
            } else {
                // 这个时候即ease = 1, 它有两种可能的状况，一种是学习队列中的卡，点击了它，一种是复习队列中的卡点击了他；
                // 不管哪种都要设置它的left；
                card.setLeft(_startingLeft(card));
                // 看这张卡片要不要重新安排学习进度，如果是普通牌组卡片的必须要，如果是过滤牌组卡片参阅过滤参数，
                boolean resched = _resched(card);
                if (conf.has("mult") && resched) {
                    // review that's lapsed
                    // 如果是lapse配置，且允许重新安排学习进度的，这有两种可能，一是lapse,二是过滤牌组的
                    // 新卡学习过程中不会走到这里来；
                    try {
                        card.setIvl(Math.max(Math.max(1, (int) (card.getIvl() * conf.getDouble("mult"))), conf.getInt("minInt")));
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    // new card; no ivl adjustment
                    // pass
                }
                if (resched && card.getODid() != 0) {
                    card.setODue(mToday + 1);
                }
            }
            // 返回距离下一学习阶段还需要的延迟时间，
            int delay = _delayForGrade(conf, card.getLeft());
            if (card.getDue() < Utils.now()) {
                // not collapsed; add some randomness
                delay *= (1 + (new Random().nextInt(25) / 100));
            }
            // 为要继续学习的卡片，即进入学习队列的卡片设置到期时间，这个到期时间将是以秒为单位的整数；
            card.setDue((int) (Utils.now() + delay));

            // due today? 如果这张学习卡片的到期时间小于今天的截至时间，则
            if (card.getDue() < mDayCutoff) {
                mLrnCount += card.getLeft() / 1000;
                // if the queue is not empty and there's nothing else to do, make
                // sure we don't put it at the head of the queue and end up showing
                // it twice in a row
                card.setQueue(1);
                if (!mLrnQueue.isEmpty() && mRevCount == 0 && mNewCount == 0) {
                    // 如果复习队列空了，新卡队列也空了，这时候要保证，这张卡的过期时间不是最小的，避免同张卡片连续出现；
                    long smallestDue = mLrnQueue.getFirst()[0];
                    card.setDue(Math.max(card.getDue(), smallestDue + 1));
                }
                // 将这张卡片按准确的顺序放入学习队列中；
                _sortIntoLrn(card.getDue(), card.getId());
                // 如果这张卡的到期时间超越今天时间极限，则：
            } else {
                // the card is due in one or more days, so we need to use the day learn queue
                long ahead = ((card.getDue() - mDayCutoff) / 86400) + 1;
                card.setDue(mToday + ahead);
                // 将其卡片设置到天学习队列；
                card.setQueue(3);
            }
        }
        _logLrn(card, ease, conf, leaving, type, lastLeft);
    }

    // 返回距离下一学习阶段还需要的延迟时间，
    private int _delayForGrade(JSONObject conf, int left) {
        // 还剩下学习的次数
        left = left % 1000;
        try {
            double delay;
            JSONArray ja = conf.getJSONArray("delays");
            int len = ja.length();
            try {
                // 下一次学习的延迟时间是多少？
                // 如果有2个阶段的学习，剩下1次学习，则，要进行第（2-1）+1 次啦，它的延续时间是dalays(2-1)
                // 如果有2个阶段的学习，还剩下2次嘘唏，则，要进行（2-2）+1 次啦，它的延迟时间是dalays(2-2);
                delay = ja.getDouble(len - left);
            } catch (JSONException e) {
            	if (conf.getJSONArray("delays").length() > 0) {
            		delay = conf.getJSONArray("delays").getDouble(0);
            	} else {
            		// user deleted final step; use dummy value
            		delay = 1.0;
            	}
            }
            return (int) (delay * 60.0);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 返回学习状态的配置信息
    private JSONObject _lrnConf(Card card) {
        if (card.getType() == 2) {
            //返回当前卡片的牌组的dconf对失误的卡片的配置信息
            return _lapseConf(card);
        } else {
            // 返回当前卡片所在的牌组的dconf中的对于新卡片的配置信息；
            return _newConf(card);
        }
    }

    /**
     *
     * @param card
     * @param conf  学习状态的配置信息可能是new的也可能是lapse的；
     * @param early
     */
    private void _rescheduleAsRev(Card card, JSONObject conf, boolean early) {
        boolean lapse = (card.getType() == 2); //是不是失误，或是过滤牌组而进入学习队列的？
        // 如果走入复习队列之前是因为失误，或是过滤牌组，
        if (lapse) {
            if (_resched(card)) {
                // 是否重新安排学习计划；首先判断卡片所在的牌组是不是过滤牌组，则是因为失误，而进入学习队列的，则返回true
                // 如果卡片所在的牌组是过滤牌组，则返回配置文件conf的resched属性值

                // 根据odue设置新的due;如果是失误造成的，则due的日期设置成明天，否则拿明天和原来的odue比较，哪个大用哪个。
                card.setDue(Math.max(mToday + 1, card.getODue()));
            } else {
                card.setDue(card.getODue());
            }
            card.setODue(0);
        } else {
            // 这种情况只有是新卡走入复习队列才能进来，early = true 表示是点击新卡学习中的第三个按钮进来的，
            // early = false 表示，是在新卡学习的第二次点击第二个按钮进来的，
            _rescheduleNew(card, conf, early);
        }
        card.setQueue(2);
        card.setType(2);
        // if we were dynamic, graduating means moving back to the old deck
        boolean resched = _resched(card);
        if (card.getODid() != 0) {
            card.setDid(card.getODid());
            card.setODue(0);
            card.setODid(0);
            // if rescheduling is off, it needs to be set back to a new card
            if (!resched && !lapse) {
                card.setType(0);
                card.setQueue(card.getType());
                card.setDue(mCol.nextID("pos"));
            }
        }
    }

    //开始剩余次数，比如返回1002，总共还要学习2次，但今天只能在学习一次，
    private int _startingLeft(Card card) {
        try {
            JSONObject conf;
        	if (card.getType() == 2) {
                // 返回失误状态的配置信息
        		conf = _lapseConf(card);
        	} else {
                // 返回学习状态的配置信息
        		conf = _lrnConf(card);
        	}
            int tot = conf.getJSONArray("delays").length(); //tot反应了延迟的步骤次数；
            int tod = _leftToday(conf.getJSONArray("delays"), tot); // 截至到今天结束，剩余的能被完成的步骤数量；
            return tot + tod * 1000; //tod=2002, 说明还有两次，今天能完成，1002说明还有两次的学习，今天只能在学一次了。
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /* the number of steps that can be completed by the day cutoff
    * 截至到今天结束，剩余的能被完成的步骤数量；
    * */
    private int _leftToday(JSONArray delays, int left) {
        return _leftToday(delays, left, 0);
    }

    /* the number of steps that can be completed by the day cutoff
    * 截至到今天结束，剩余的能被完成的步骤数量；
    * */
    private int _leftToday(JSONArray delays, int left, long now) {
        if (now == 0) {
            now = Utils.intNow();
        }
        int ok = 0;
        int offset = Math.min(left, delays.length());
        for (int i = 0; i < offset; i++) {
            try {
                now += (int) (delays.getDouble(delays.length() - offset + i) * 60.0);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            if (now > mDayCutoff) {
                break;
            }
            ok = i;
        }
        return ok + 1;
    }


    private int _graduatingIvl(Card card, JSONObject conf, boolean early) {
        return _graduatingIvl(card, conf, early, true);
    }

    /**
     * 当新卡片毕业后，会进入复习队列，即新卡片进入复习队列，有两种情况，一种是学习中点击第三个按钮进入复习队列early = true,
     * 一种是经过至少一次学习之后点击第二个按钮进入复习队列的，这时early = false;
     * @param card
     * @param conf
     * @param early 是点击第三个按钮进入的，还是点击第二个按钮进入的；
     * @param adj 是否要对ivl进行调节呢？
     * @return
     */
    private int _graduatingIvl(Card card, JSONObject conf, boolean early, boolean adj) {
        if (card.getType() == 2) {
            // lapsed card being relearnt
            if (card.getODid() != 0) {
                try {
                    if (conf.getBoolean("resched")) {
                        //为动态卡片返回一个ivl，为动态卡片
                        return _dynIvlBoost(card);
                    }
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
            return card.getIvl();
        }
        // 如果进来的是全新的卡片学习而造成的进入复习队列，即毕业，按照下面方法做；
        int ideal;
        JSONArray ja;
        try {
            ja = conf.getJSONArray("ints");
            if (!early) {
                // graduate
                ideal = ja.getInt(0);
            } else {
                ideal = ja.getInt(1);
            }
            if (adj) {
                // 返回一个调节过的interval
                return _adjRevIvl(card, ideal);
            } else {
                return ideal;
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /* Reschedule a new card that's graduated for the first time.只有新卡片才会走到这里来；
    * 设置一个新卡片的复习进度安排，即设置卡片的 ivl 和 due
    * 这个新卡片进入学习队列，可能是第一次出现就进去，这时候early = true;
    * 也可能是 学习了一次才进去，这时early = false；
    * */
    private void _rescheduleNew(Card card, JSONObject conf, boolean early) {
        card.setIvl(_graduatingIvl(card, conf, early));
        card.setDue(mToday + card.getIvl());
        try {
            // 新卡片走入复习队列后，卡片的因子设置成和初始化因子；
            card.setFactor(conf.getInt("initialFactor"));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    private void _logLrn(Card card, int ease, JSONObject conf, boolean leaving, int type, int lastLeft) {
        int lastIvl = -(_delayForGrade(conf, lastLeft));
        int ivl = leaving ? card.getIvl() : -(_delayForGrade(conf, card.getLeft()));
        log(card.getId(), mCol.usn(), ease, ivl, lastIvl, card.getFactor(), card.timeTaken(), type);
    }


    private void log(long id, int usn, int ease, int ivl, int lastIvl, int factor, int timeTaken, int type) {
        try {
            mCol.getDb().execute("INSERT INTO revlog VALUES (?,?,?,?,?,?,?,?,?)",
                    new Object[]{Utils.now() * 1000, id, usn, ease, ivl, lastIvl, factor, timeTaken, type});
        } catch (SQLiteConstraintException e) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e1) {
                throw new RuntimeException(e1);
            }
            log(id, usn, ease, ivl, lastIvl, factor, timeTaken, type);
        }
    }


    public void removeLrn() {
    	removeLrn(null);
    }

    /* Remove cards from the learning queues. */
    private void removeLrn(long[] ids) {
        String extra;
        if (ids != null && ids.length > 0) {
            extra = " AND id IN " + Utils.ids2str(ids);
        } else {
            // benchmarks indicate it's about 10x faster to search all decks with the index than scan the table
            extra = " AND did IN " + Utils.ids2str(mCol.getDecks().allIds());
        }
        // review cards in relearning
        mCol.getDb().execute(
                "update cards set due = odue, queue = 2, mod = " + Utils.intNow() +
                ", usn = " + mCol.usn() + ", odue = 0 where queue IN (1,3) and type = 2 " + extra);
        // new cards in learning
        forgetCards(Utils.arrayList2array(mCol.getDb().queryColumn(Long.class, "SELECT id FROM cards WHERE queue IN (1,3) " + extra, 0)));
    }


    private int _lrnForDeck(long did) {
        try {
            int cnt = mCol.getDb().queryScalar(
                    "SELECT sum(left / 1000) FROM (SELECT left FROM cards WHERE did = " + did
                            + " AND queue = 1 AND due < " + (Utils.intNow() + mCol.getConf().getInt("collapseTime"))
                            + " LIMIT " + mReportLimit + ")");
            return cnt + mCol.getDb().queryScalar(
                    "SELECT count() FROM (SELECT 1 FROM cards WHERE did = " + did
                            + " AND queue = 3 AND due <= " + mToday
                            + " LIMIT " + mReportLimit + ")");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Reviews ****************************************************************** *****************************
     */

    private int _deckRevLimit(long did) {
        try {
            return _deckNewLimit(did, Sched.class.getDeclaredMethod("_deckRevLimitSingle", JSONObject.class));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 计算单个牌组 的复习卡片的数量的限制
     * @param d 一个牌组的描述信息，
     * @return 输入牌组的 今天还需要复习的卡片的数量；
     */
    private int _deckRevLimitSingle(JSONObject d) {
        try {
            if (d.getInt("dyn") != 0) {
                // 如果不是过滤牌组，则返回mPeportLimit
                return mReportLimit;
            }
            // 如果是普通牌组，获取这个牌组对应的配置信息： dconf
            JSONObject c = mCol.getDecks().confForDid(d.getLong("id"));
            // 从牌组的配置信息中读出每天复习的上限a, 减去牌组中的revToday属性里记录的今天已经复习的卡片的数量，其差值与0比较取最大值，返回
            return Math.max(0, c.getJSONObject("rev").getInt("perDay") - d.getJSONArray("revToday").getInt(1));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    //在限制范围内，当天要学习的复习卡片的实际数量；
    public int _revForDeck(long did, int lim) {
    	lim = Math.min(lim, mReportLimit);
    	return mCol.getDb().queryScalar("SELECT count() FROM (SELECT 1 FROM cards WHERE did = " + did + " AND queue = 2 AND due <= " + mToday + " LIMIT " + lim + ")");
    }

    // 重新设置今天需要复习的卡片数量
    private void _resetRevCount() {
        try {
            //_deckRevLimitSingle方法返回，按照dconf配置每天复习上限，减去今天已经复习的卡片数量，返回今天还需要学习的卡片数量
            //_cntFnRev方法返回，针对该牌组，用户自定义增加的有效复习卡片数量
            //_walkingCount返回所有活动牌组的当天要复习的有效卡片数量总和，
            mRevCount = _walkingCount(Sched.class.getDeclaredMethod("_deckRevLimitSingle", JSONObject.class),
                    Sched.class.getDeclaredMethod("_cntFnRev", long.class, int.class));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }


    // Dynamically invoked in _walkingCount, passed as a parameter in _resetRevCount
    // 返回今天实际还要复习的卡片数量；
    @SuppressWarnings("unused")
    private int _cntFnRev(long did, int lim) {
        return mCol.getDb().queryScalar(
                // 获得这个牌组did中，复习的，今天过期的，前lim个卡片，将其返回，
                "SELECT count() FROM (SELECT id FROM cards WHERE did = " + did + " AND queue = 2 and due <= " + mToday
                        + " LIMIT " + lim + ")");
    }

    //重设复习项目
    private void _resetRev() {
        _resetRevCount(); //重新计算活动牌组中当天要学习的复习卡片的总数量
        mRevQueue.clear(); //清除复习卡片队列
        mRevDids = mCol.getDecks().active();
    }


    private boolean _fillRev() {
        if (!mRevQueue.isEmpty()) {
            return true;
        }
        if (mRevCount == 0) {
            return false;
        }
        while (mRevDids.size() > 0) {
            long did = mRevDids.getFirst();
            int lim = Math.min(mQueueLimit, _deckRevLimit(did));
            Cursor cur = null;
            if (lim != 0) {
                mRevQueue.clear();
                // fill the queue with the current did
                try {
                    cur = mCol
                            .getDb()
                            .getDatabase()
                            .rawQuery(
                                    "SELECT id FROM cards WHERE did = " + did + " AND queue = 2 AND due <= " + mToday
                                            + " LIMIT " + lim, null);
                    while (cur.moveToNext()) {
                        mRevQueue.add(cur.getLong(0));
                    }
                } finally {
                    if (cur != null && !cur.isClosed()) {
                        cur.close();
                    }
                }
                if (!mRevQueue.isEmpty()) {
                    // ordering
                    try {
                        if (mCol.getDecks().get(did).getInt("dyn") != 0) {
                            // dynamic decks need due order preserved
                            // Note: libanki reverses mRevQueue and returns the last element in _getRevCard().
                            // AnkiDroid differs by leaving the queue intact and returning the *first* element
                            // in _getRevCard().
                        } else {
                            Random r = new Random();
                            r.setSeed(mToday);
                            Collections.shuffle(mRevQueue, r);
                        }
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                    // is the current did empty?
                    if (mRevQueue.size() < lim) {
                        mRevDids.remove();
                    }
                    return true;
                }
            }
            // nothing left in the deck; move to next
            mRevDids.remove();
        }
        if (mRevCount != 0) {
            // if we didn't get a card but the count is non-zero,
            // we need to check again for any cards that were
            // removed from the queue but not buried
            _resetRev();
            return _fillRev();
        }
        return false;
    }


    private Card _getRevCard() {
        if (_fillRev()) {
            mRevCount -= 1;
            return mCol.getCard(mRevQueue.remove());
        } else {
            return null;
        }
    }


    public int totalRevForCurrentDeck() {
        return mCol.getDb().queryScalar(String.format(Locale.US,
        		"SELECT count() FROM cards WHERE id IN (SELECT id FROM cards WHERE did IN %s AND queue = 2 AND due <= %d LIMIT %s)",
        		Utils.ids2str(mCol.getDecks().active()), mToday, mReportLimit));
    }


    /**
     * Answering a review card **************************************************
     * 对一个复习队列中的卡片做了回答之后该如何操作；
     * *********************************************
     */

    private void _answerRevCard(Card card, int ease) {
        int delay = 0;
        if (ease == 1) {
            delay = _rescheduleLapse(card);
        } else {
            // 如果回答的按钮不是第一个，如何处理？
            _rescheduleRev(card, ease);
        }
        _logRev(card, ease, delay);
    }

    // 当一个复习中的卡片，点击第一个按钮的时候，将执行意下操作；
    private int _rescheduleLapse(Card card) {
        JSONObject conf;
        try {
            conf = _lapseConf(card);
            card.setLastIvl(card.getIvl());
            if (_resched(card)) {
                // 如果从新设置卡进度；
                card.setLapses(card.getLapses() + 1);
                // 设置lapse间隔；
                card.setIvl(_nextLapseIvl(card, conf));
                // factor为（1300， 2500-200）的最大值；
                card.setFactor(Math.max(1300, card.getFactor() - 200));
                // 设置过期时间；
                card.setDue(mToday + card.getIvl());
                // if it's a filtered deck, update odue as well
                if (card.getODid() != 0) {
                    card.setODue(card.getDue());
                }
            }
            // if suspended as a leech, nothing to do 如果被认为是水蛭卡，暂停掉的时候，什么也不做；
            int delay = 0;
            if (_checkLeech(card, conf) && card.getQueue() == -1) {
                return delay;
            }
            // if no relearning steps, nothing to do 如果没有重新学习阶段，什么也不做；
            if (conf.getJSONArray("delays").length() == 0) {
                return delay;
            }
            // record rev due date for later
            if (card.getODue() == 0) {
                card.setODue(card.getDue());
            }
            // 算出距离下一阶段的延迟时间；
            delay = _delayForGrade(conf, 0);
            card.setDue((long) (delay + Utils.now()));
            // 设置今天还有多少次学习，今天要学习多少次；
            card.setLeft(_startingLeft(card));
            // queue 1
            if (card.getDue() < mDayCutoff) {
                mLrnCount += card.getLeft() / 1000;
                card.setQueue(1);
                _sortIntoLrn(card.getDue(), card.getId());
            } else {
                // day learn queue
                long ahead = ((card.getDue() - mDayCutoff) / 86400) + 1;
                card.setDue(mToday + ahead);
                card.setQueue(3);
            }
            return delay;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 返回lapse后的间隔；
    private int _nextLapseIvl(Card card, JSONObject conf) {
        try {
            return Math.max(conf.getInt("minInt"), (int)(card.getIvl() * conf.getDouble("mult")));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 复习卡片的时候，卡片来自复习队列，回答不是第一个该如何处理呢？
    private void _rescheduleRev(Card card, int ease) {
        // update interval
        card.setLastIvl(card.getIvl());
        if (_resched(card)) {
            // 更新复习间隔，给出参数，当前卡片，和被点击的按钮序号，序号>=2;
            _updateRevIvl(card, ease);
            // then the rest
            // 修改卡片因子；
            card.setFactor(Math.max(1300, card.getFactor() + FACTOR_ADDITION_VALUES[ease - 2]));
            card.setDue(mToday + card.getIvl());
        } else {
            card.setDue(card.getODue());
        }
        if (card.getODid() != 0) {
            card.setDid(card.getODid());
            card.setODid(0);
            card.setODue(0);
        }
    }


    private void _logRev(Card card, int ease, int delay) {
        log(card.getId(), mCol.usn(), ease, ((delay != 0) ? (-delay) : card.getIvl()), card.getLastIvl(),
                card.getFactor(), card.timeTaken(), 1);
    }


    /**
     * Interval management ******************************************************
     * *****************************************
     */

    /**
     * Ideal next interval for CARD, given EASE.
     * 计算获取下一次卡片出来的间隔；
     */
    private int _nextRevIvl(Card card, int ease) {
        try {
            // 按照卡片的复习时间，你已经逾期几天，拖延几天，没有复习这张卡片了,假设预期5天，之前的ivl是4，
            long delay = _daysLate(card);
            int interval = 0;
            // 拿出复习的配置信息；
            JSONObject conf = _revConf(card);
            // 卡片的初始因子是2500；每失误一次，就会减少200；
            double fct = card.getFactor() / 1000.0;
            // 返回应用约束的间隔；
            int ivl2 = _constrainedIvl((int)((card.getIvl() + delay/4) * 1.2), conf, card.getIvl());
            int ivl3 = _constrainedIvl((int)((card.getIvl() + delay/2) * fct), conf, ivl2);
            int ivl4 = _constrainedIvl((int)((card.getIvl() + delay) * fct * conf.getDouble("ease4")), conf, ivl3);
            if (ease == 2) {
                interval = ivl2;
            } else if (ease == 3) {
                interval = ivl3;
            } else if (ease == 4) {
            	interval = ivl4;
            }
            // interval capped?
            return Math.min(interval, conf.getInt("maxIvl"));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 返回回个模糊的随机的interval
    private int _fuzzedIvl(int ivl) {
        int[] minMax = _fuzzedIvlRange(ivl);
        // Anki's python uses random.randint(a, b) which returns x in [a, b] while the eq Random().nextInt(a, b)
        // returns x in [0, b-a), hence the +1 diff with libanki
        return (new Random().nextInt(minMax[1] - minMax[0] + 1)) + minMax[0];
    }

    // 模糊化间隔范围
    public int[] _fuzzedIvlRange(int ivl) {
        int fuzz;
        if (ivl < 2) {
            return new int[]{1, 1};
        } else if (ivl == 2) {
            return new int[]{2, 3};
        } else if (ivl < 7) {
            fuzz = (int)(ivl * 0.25);
        } else if (ivl < 30) {
            fuzz = Math.max(2, (int)(ivl * 0.15));
        } else {
            fuzz = Math.max(4, (int)(ivl * 0.05));
        }
        // fuzz at least a day
        fuzz = Math.max(fuzz, 1);
        return new int[]{ivl - fuzz, ivl + fuzz};
    }


    /** Integer interval after interval factor and prev+1 constraints applied
     *  返回应用约束的间隔；
     * */
    private int _constrainedIvl(int ivl, JSONObject conf, double prev) {
    	double newIvl = ivl;
    	newIvl = ivl * conf.optDouble("ivlFct",1.0);
        return (int) Math.max(newIvl, prev + 1);
    }


    /**
     * Number of days later than scheduled.
     * 按照卡片的复习时间，你已经逾期几天没有复习这张卡片了
     */
    private long _daysLate(Card card) {
        long due = card.getODid() != 0 ? card.getODue() : card.getDue();
        return Math.max(0, mToday - due);
    }

    // 更新复习间隔，给出参数，当前卡片，和被点击的按钮序号，序号>=2;
    private void _updateRevIvl(Card card, int ease) {
        int idealIvl = _nextRevIvl(card, ease);
        card.setIvl(_adjRevIvl(card, idealIvl));
    }

    // 返回一个调节过的ivl
    private int _adjRevIvl(Card card, int idealIvl) {
        if (mSpreadRev) {
            idealIvl = _fuzzedIvl(idealIvl);
        }
        return idealIvl;
    }


    /**
     * Dynamic deck handling ******************************************************************
     * *****************************
     */

    /* Rebuild a dynamic deck. */
    public void rebuildDyn() {
        rebuildDyn(0);
    }


    public List<Long> rebuildDyn(long did) {
        if (did == 0) {
            did = mCol.getDecks().selected();
        }
        JSONObject deck = mCol.getDecks().get(did);
        try {
            if (deck.getInt("dyn") == 0) {
                Timber.e("error: deck is not a filtered deck");
                return null;
            }
        } catch (JSONException e1) {
            throw new RuntimeException(e1);
        }
        // move any existing cards back first, then fill
        emptyDyn(did);
        List<Long> ids = _fillDyn(deck);
        if (ids.isEmpty()) {
            return null;
        }
        // and change to our new deck
        mCol.getDecks().select(did);
        return ids;
    }


    private List<Long> _fillDyn(JSONObject deck) {
        JSONArray terms;
        List<Long> ids;
        try {
            terms = deck.getJSONArray("terms").getJSONArray(0);
            String search = terms.getString(0);
            int limit = terms.getInt(1);
            int order = terms.getInt(2);
            String orderlimit = _dynOrder(order, limit);
            if (!TextUtils.isEmpty(search.trim())) {
                search = String.format(Locale.US, "(%s)", search);
            }
            search = String.format(Locale.US, "%s -is:suspended -is:buried -deck:filtered", search);
            ids = mCol.findCards(search, orderlimit);
            if (ids.isEmpty()) {
                return ids;
            }
            // move the cards over
            mCol.log(deck.getLong("id"), ids);
            _moveToDyn(deck.getLong("id"), ids);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return ids;
    }


    public void emptyDyn(long did) {
        emptyDyn(did, null);
    }


    public void emptyDyn(long did, String lim) {
        if (lim == null) {
            lim = "did = " + did;
        }
        mCol.log(mCol.getDb().queryColumn(Long.class, "select id from cards where " + lim, 0));
        // move out of cram queue
        mCol.getDb().execute(
                "update cards set did = odid, queue = (case when type = 1 then 0 " +
                "else type end), type = (case when type = 1 then 0 else type end), " +
                "due = odue, odue = 0, odid = 0, usn = ? where " + lim,
                new Object[] { mCol.usn() });
    }


    public void remFromDyn(long[] cids) {
        emptyDyn(0, "id IN " + Utils.ids2str(cids) + " AND odid");
    }


    /**
     * Generates the required SQL for order by and limit clauses, for dynamic decks.
     *
     * @param o deck["order"]
     * @param l deck["limit"]
     * @return The generated SQL to be suffixed to "select ... from ... order by "
     */
    private String _dynOrder(int o, int l) {
        String t;
        switch (o) {
            case Consts.DYN_OLDEST:
                t = "c.mod";
                break;
            case Consts.DYN_RANDOM:
                t = "random()";
                break;
            case Consts.DYN_SMALLINT:
                t = "ivl";
                break;
            case Consts.DYN_BIGINT:
                t = "ivl desc";
                break;
            case Consts.DYN_LAPSES:
                t = "lapses desc";
                break;
            case Consts.DYN_ADDED:
                t = "n.id";
                break;
            case Consts.DYN_REVADDED:
                t = "n.id desc";
                break;
            case Consts.DYN_DUE:
                t = "c.due";
                break;
            case Consts.DYN_DUEPRIORITY:
                t = String.format(Locale.US,
                        "(case when queue=2 and due <= %d then (ivl / cast(%d-due+0.001 as real)) else 100000+due end)",
                        mToday, mToday);
                break;
            default:
            	// if we don't understand the term, default to due order
            	t = "c.due";
        }
        return t + " limit " + l;
    }


    private void _moveToDyn(long did, List<Long> ids) {
        ArrayList<Object[]> data = new ArrayList<Object[]>();
        long t = Utils.intNow();
        int u = mCol.usn();
        for (long c = 0; c < ids.size(); c++) {
            // start at -100000 so that reviews are all due
            data.add(new Object[] { did, -100000 + c, u, ids.get((int) c) });
        }
        // due reviews stay in the review queue. careful: can't use "odid or did", as sqlite converts to boolean
        String queue = "(CASE WHEN type = 2 AND (CASE WHEN odue THEN odue <= " + mToday +
                " ELSE due <= " + mToday + " END) THEN 2 ELSE 0 END)";
        mCol.getDb().executeMany(
                "UPDATE cards SET odid = (CASE WHEN odid THEN odid ELSE did END), " +
                        "odue = (CASE WHEN odue THEN odue ELSE due END), did = ?, queue = " +
                        queue + ", due = ?, usn = ? WHERE id = ?", data);
    }

    // 为动态卡片返回一个ivl，它会考虑上一次的ivl,最后一次看完距离现在的时间，以及卡片因子来计算一个合适的ivl返回；
    private int _dynIvlBoost(Card card) {
        if (card.getODid() == 0 || card.getType() != 2 || card.getFactor() == 0) {
            // 如果不是过滤的卡片，或不是复习的卡片，或卡片因子等于0的，直接返回0；
            Timber.e("error: deck is not a filtered deck");
            return 0;
        }
        //card.getODue() - mToday，表示如果上次说间隔是7天，第10天到期，今天是第8天了，说明还有两天到期，上次的间隔，减去还剩下的
        //天数，就是，自从上次看了之后，到今天已经逝去的天数，的时间，
        long elapsed = card.getIvl() - (card.getODue() - mToday);
        // 根据卡片因子，和已经逝去的时间，以及上次的间隔，来计算下一次的间隔时间，
        double factor = ((card.getFactor() / 1000.0) + 1.2) / 2.0;
        int ivl = Math.max(1, Math.max(card.getIvl(), (int) (elapsed * factor)));
        // 取出卡片所在的牌组的dconf的rev配置信息，如果是过滤牌组，就取出原来所在牌组的相关信息
        JSONObject conf = _revConf(card);
        try {
            // 保证ivl不超出限制；
            return Math.min(conf.getInt("maxIvl"), ivl);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Leeches ****************************************************************** *****************************
     */

    /** Leech handler. True if card was a leech. */
    private boolean _checkLeech(Card card, JSONObject conf) {
        int lf;
        try {
            lf = conf.getInt("leechFails");
            if (lf == 0) {
                return false;
            }
            // if over threshold or every half threshold reps after that
            if (card.getLapses() >= lf && (card.getLapses() - lf) % Math.max(lf / 2, 1) == 0) {
                // add a leech tag
                Note n = card.note();
                n.addTag("leech");
                n.flush();
                // handle
                if (conf.getInt("leechAction") == 0) {
                    // if it has an old due, remove it from cram/relearning
                    if (card.getODue() != 0) {
                        card.setDue(card.getODue());
                    }
                    if (card.getODid() != 0) {
                        card.setDid(card.getODid());
                    }
                    card.setODue(0);
                    card.setODid(0);
                    card.setQueue(-1);
                }
                // notify UI
                if (mContextReference != null) {
                    Context context = mContextReference.get();
                    Hooks.getInstance(context).runHook("leech", card, context);
                }
                return true;
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return false;
    }


    /**
     * Tools ******************************************************************** ***************************
     */
    // 返回输入卡片的所在牌组的dconf
    public JSONObject _cardConf(Card card) {
        return mCol.getDecks().confForDid(card.getDid());
    }

    // 返回当前卡片所在的牌组的dconf中的对于新卡片的配置信息；
    private JSONObject _newConf(Card card) {
        try {
            // 返回输入卡片的所在牌组的dconf
            JSONObject conf = _cardConf(card);
            // normal deck
            if (card.getODid() == 0) {
                // 如果卡片来自普通牌组；
                return conf.getJSONObject("new");
            }
            // dynamic deck; override some attributes, use original deck for others
            // oconf是卡片原来所在的牌组的dconf
            JSONObject oconf = mCol.getDecks().confForDid(card.getODid());
            JSONArray delays = conf.optJSONArray("delays");
            if (delays == null) {
                delays = oconf.getJSONObject("new").getJSONArray("delays");
            }
            JSONObject dict = new JSONObject();
            // original deck
            dict.put("ints", oconf.getJSONObject("new").getJSONArray("ints"));
            dict.put("initialFactor", oconf.getJSONObject("new").getInt("initialFactor"));
            dict.put("bury", oconf.getJSONObject("new").optBoolean("bury", true));
            // overrides
            dict.put("delays", delays);
            dict.put("separate", conf.getBoolean("separate"));
            dict.put("order", Consts.NEW_CARDS_DUE); // Consts.NEW_CARDS_DUE即新卡，按照插入顺序，而非随机顺序
            dict.put("perDay", mReportLimit);
            return dict;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    //返回当前卡片的牌组的dconf对失误的卡片的配置信息
    private JSONObject _lapseConf(Card card) {
        try {
            // 返回输入卡片的所在牌组的dconf
            JSONObject conf = _cardConf(card);
            // normal deck
            if (card.getODid() == 0) {
                return conf.getJSONObject("lapse");
            }
            // dynamic deck; override some attributes, use original deck for others
            JSONObject oconf = mCol.getDecks().confForDid(card.getODid());
            JSONArray delays = conf.optJSONArray("delays");
            if (delays == null) {
                delays = oconf.getJSONObject("lapse").getJSONArray("delays");
            }
            JSONObject dict = new JSONObject();
            // original deck
            dict.put("minInt", oconf.getJSONObject("lapse").getInt("minInt"));
            dict.put("leechFails", oconf.getJSONObject("lapse").getInt("leechFails"));
            dict.put("leechAction", oconf.getJSONObject("lapse").getInt("leechAction"));
            dict.put("mult", oconf.getJSONObject("lapse").getDouble("mult"));
            // overrides
            dict.put("delays", delays);
            dict.put("resched", conf.getBoolean("resched"));
            return dict;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    //取出卡片所在的牌组的dconf的rev配置信息，如果是过滤牌组，就取出原来所在牌组的相关信息
    private JSONObject _revConf(Card card) {
        try {
            //去除卡片card所在的牌组的dconf
            JSONObject conf = _cardConf(card);
            // normal deck
            if (card.getODid() == 0) {
                // 如果是普通牌组，则返回dconf的rev配置信息
                return conf.getJSONObject("rev");
            }
            // dynamic deck如果是过滤牌组，就取出原来牌组的dconf的rev配置信息
            return mCol.getDecks().confForDid(card.getODid()).getJSONObject("rev");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // 获取活动的牌组，并链接成字符串，
    public String _deckLimit() {
        return Utils.ids2str(mCol.getDecks().active());
    }

    // 是否重新安排学习计划；首先判断卡片所在的牌组是不是过滤牌组，则是因为失误，而进入学习队列的，则返回true
    // 如果卡片所在的牌组是过滤牌组，则返回配置文件conf的resched属性值
    private boolean _resched(Card card) {
        JSONObject conf = _cardConf(card); // 获取卡片所在的牌组的配置文件dconf
        try {
            // 如果是不是动态卡片
            if (conf.getInt("dyn") == 0) {
                // 即，如果是因为失误而造成的进入学习队列，要重新安排学习进度，则返回true，
                return true;
            }
            // 程序走到这里，说明是由于过滤牌组而走入学习队列，是否要重新安排学习进度，根据过滤牌组的配置而定，
            return conf.getBoolean("resched");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Daily cutoff ************************************************************* **********************************
     * This function uses GregorianCalendar so as to be sensitive to leap years, daylight savings, etc.
     */

    private void _updateCutoff() {
        int oldToday = mToday;
        // days since col created
        // (Utils.now()-----(System.currentTimeMillis() / 1000.0)--->1431814401699 这是一个13位的长整形数字，单位是秒，
        // mToday是自从这个集合被创建哪天算起，截至到今天，已过去几个整天了，
        mToday = (int) ((Utils.now() - mCol.getCrt()) / 86400); // 86400=60*60*24, one day;
        // end of day cutoff mDayCutoff是今天结束的那一刻，给个界定
        mDayCutoff = mCol.getCrt() + ((mToday + 1) * 86400);  // 计算截至日期的时间，精确到秒
        // crt 这个参数，在当前文件中的时间是2015/12/20---4：00；
        // 这样在就可以给所谓的今天一个时间范围，即 mToday*86400 到 (mToday + 1) * 86400；
        if (oldToday != mToday) {
            mCol.log(mToday, mDayCutoff);
        }
        // update all daily counts, but don't save decks to prevent needless conflicts. we'll save on card answer
        // instead 更新所有的 每日卡片计算，但是不保存到decks中，目的是为了避免不必要的冲突，我们将在回答卡片的时候保存到deck中；
        for (JSONObject deck : mCol.getDecks().all()) {
            update(deck); // 更新deck中的newToday, revToday, lrnToday, timeToday的这四个数组，更新其第一个元素为mToday
        }
        // unbury if the day has rolled over 如果这一天滚完，就取消搁置
        int unburied = mCol.getConf().optInt("lastUnburied", 0);
        if (unburied < mToday) {
            // 如果配置文件中的lastUnburied这个属性值小于mToday，则取消搁置；
            unburyCards();
        }
    }

    // update(deck)，更新牌组中的newToday, revToday, lrnToday, timeToday这些属性数组的第一个元素，为mToday，
    private void update(JSONObject g) {
        for (String t : new String[] { "new", "rev", "lrn", "time" }) {
            // 即newToday, revToday, lrnToday, timeToday
            String key = t + "Today";
            try {
                if (g.getJSONArray(key).getInt(0) != mToday) {
                    JSONArray ja = new JSONArray();
                    ja.put(mToday); //假设mToday=183， 即此牌组集合被创建183天了，
                    ja.put(0);
                    // ja则为：[183, 0]
                    g.put(key, ja);
                }
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
    }


    public void _checkDay() {
        // check if the day has rolled over检查今天到头了吗？
        if (Utils.now() > mDayCutoff) {
            reset();
        }
    }


    /**
     * Deck finished state ******************************************************
     * *****************************************
     */

    public CharSequence finishedMsg(Context context) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(context.getString(R.string.studyoptions_congrats_finished));
        StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
        sb.setSpan(boldSpan, 0, sb.length(), 0);
        sb.append(_nextDueMsg(context));
        // sb.append("\n\n");
        // sb.append(_tomorrowDueMsg(context));
        return sb;
    }


    public String _nextDueMsg(Context context) {
        StringBuilder sb = new StringBuilder();
        if (revDue()) {
            sb.append("\n\n");
            sb.append(context.getString(R.string.studyoptions_congrats_more_rev));
        }
        if (newDue()) {
            sb.append("\n\n");
            sb.append(context.getString(R.string.studyoptions_congrats_more_new));
        }
        if (haveBuried()) {
            String now;
            if (mHaveCustomStudy) {
                now = " " + context.getString(R.string.sched_unbury_action);
            } else {
                now = "";
            }
            sb.append("\n\n");
            sb.append("" + context.getString(R.string.sched_has_buried) + now);
        }
        try {
            if (mHaveCustomStudy && mCol.getDecks().current().getInt("dyn") == 0) {
                sb.append("\n\n");
                sb.append(context.getString(R.string.studyoptions_congrats_custom));
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return sb.toString();
    }


    /** true if there are any rev cards due. */
    public boolean revDue() {
        return mCol.getDb()
                .queryScalar(
                        "SELECT 1 FROM cards WHERE did IN " + _deckLimit() + " AND queue = 2 AND due <= " + mToday
                                + " LIMIT 1") != 0;
    }


    /** true if there are any new cards due. */
    public boolean newDue() {
        return mCol.getDb().queryScalar("SELECT 1 FROM cards WHERE did IN " + _deckLimit() + " AND queue = 0 LIMIT 1") != 0;
    }


    public boolean haveBuried() {
        String sdids = Utils.ids2str(mCol.getDecks().active());
        int cnt = mCol.getDb().queryScalar(String.format(Locale.US,
                "select 1 from cards where queue = -2 and did in %s limit 1", sdids));
        return cnt != 0;
    }


    /**
     * Next time reports ********************************************************
     * ***************************************
     */

    /**
     * Return the next interval for a card and ease as a string.
     *
     * For a given card and ease, this returns a string that shows when the card will be shown again when the
     * specific ease button (AGAIN, GOOD etc.) is touched. This uses unit symbols like “s” rather than names
     * (“second”), like Anki desktop.
     *
     * @param context The app context, used for localization
     * @param card The card being reviewed
     * @param ease The button number (easy, good etc.)
     * @return A string like “1 min” or “1.7 mo”
     */
    public String nextIvlStr(Context context, Card card, int ease) {
        int ivl = nextIvl(card, ease);
        if (ivl == 0) {
            return context.getString(R.string.sched_end);
        }
        String s = Utils.timeQuantity(context, ivl);
        try {
            if (ivl < mCol.getConf().getInt("collapseTime")) {
                s = context.getString(R.string.less_than_time, s);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return s;
    }


    /**
     * Return the next interval for CARD, in seconds.
     */
    public int nextIvl(Card card, int ease) {
        try {
            if (card.getQueue() == 0 || card.getQueue() == 1 || card.getQueue() == 3) {
                return _nextLrnIvl(card, ease);
            } else if (ease == 1) {
                // lapsed
                JSONObject conf = _lapseConf(card);
                if (conf.getJSONArray("delays").length() > 0) {
                    return (int) (conf.getJSONArray("delays").getDouble(0) * 60.0);
                }
                return _nextLapseIvl(card, conf) * 86400;
            } else {
                // review
                return _nextRevIvl(card, ease) * 86400;
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    private int _nextLrnIvl(Card card, int ease) {
        // this isn't easily extracted from the learn code
        if (card.getQueue() == 0) {
            card.setLeft(_startingLeft(card));
        }
        JSONObject conf = _lrnConf(card);
        try {
            if (ease == 1) {
                // fail
                return _delayForGrade(conf, conf.getJSONArray("delays").length());
            } else if (ease == 3) {
                // early removal
                if (!_resched(card)) {
                    return 0;
                }
                return _graduatingIvl(card, conf, true, false) * 86400;
            } else {
                int left = card.getLeft() % 1000 - 1;
                if (left <= 0) {
                    // graduate
                    if (!_resched(card)) {
                        return 0;
                    }
                    return _graduatingIvl(card, conf, false, false) * 86400;
                } else {
                    return _delayForGrade(conf, left);
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Suspending *************************************************************** ********************************
     */

    /**
     * Suspend cards.
     */
    public void suspendCards(long[] ids) {
        mCol.log(ids);
        remFromDyn(ids);
        removeLrn(ids);
        mCol.getDb().execute(
                "UPDATE cards SET queue = -1, mod = " + Utils.intNow() + ", usn = " + mCol.usn() + " WHERE id IN "
                        + Utils.ids2str(ids));
    }


    /**
     * Unsuspend cards
     */
    public void unsuspendCards(long[] ids) {
        mCol.log(ids);
        mCol.getDb().execute(
                "UPDATE cards SET queue = type, mod = " + Utils.intNow() + ", usn = " + mCol.usn()
                        + " WHERE queue = -1 AND id IN " + Utils.ids2str(ids));
    }


    public void buryCards(long[] cids) {
        mCol.log(cids);
        remFromDyn(cids);
        removeLrn(cids);
        mCol.getDb().execute("update cards set queue=-2,mod=?,usn=? where id in " + Utils.ids2str(cids),
                new Object[]{Utils.now(), mCol.usn()});
    }


    /**
     * Bury all cards for note until next session.
     * @param nid The id of the targeted note.
     */
    public void buryNote(long nid) {
        long[] cids = Utils.arrayList2array(mCol.getDb().queryColumn(Long.class,
                "SELECT id FROM cards WHERE nid = " + nid + " AND queue >= 0", 0));
        buryCards(cids);
    }

    /**
     * Sibling spacing
     * ********************
     */

    private void _burySiblings(Card card) {
        LinkedList<Long> toBury = new LinkedList<Long>();
        JSONObject nconf = _newConf(card);
        boolean buryNew = nconf.optBoolean("bury", true);
        JSONObject rconf = _revConf(card);
        boolean buryRev = rconf.optBoolean("bury", true);
        // loop through and remove from queues
        Cursor cur = null;
        try {
            cur = mCol.getDb().getDatabase().rawQuery(String.format(Locale.US,
                    "select id, queue from cards where nid=%d and id!=%d "+
                    "and (queue=0 or (queue=2 and due<=%d))", new Object[]{card.getNid(), card.getId(), mToday}), null);
            while (cur.moveToNext()) {
                long cid = cur.getLong(0);
                int queue = cur.getInt(1);
                if (queue == 2) {
                    if (buryRev) {
                        toBury.add(cid);
                    }
                    // if bury disabled, we still discard to give same-day spacing
                    mRevQueue.remove(cid);
                } else {
                    // if bury is disabled, we still discard to give same-day spacing
                    if (buryNew) {
                        toBury.add(cid);
                    }
                    mNewQueue.remove(cid);
                }
            }
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
        // then bury
        if (toBury.size() > 0) {
            mCol.getDb().execute("update cards set queue=-2,mod=?,usn=? where id in " + Utils.ids2str(toBury),
                    new Object[] { Utils.now(), mCol.usn() });
            mCol.log(toBury);
        }
    }


    /**
     * Resetting **************************************************************** *******************************
     */

    /** Put cards at the end of the new queue. */
    public void forgetCards(long[] ids) {
        remFromDyn(ids);
        mCol.getDb().execute("update cards set type=0,queue=0,ivl=0,due=0,odue=0,factor=2500" +
                " where id in " + Utils.ids2str(ids));
        int pmax = mCol.getDb().queryScalar("SELECT max(due) FROM cards WHERE type=0");
        // takes care of mod + usn
        sortCards(ids, pmax + 1);
        mCol.log(ids);
    }


    /**
     * Put cards in review queue with a new interval in days (min, max).
     *
     * @param ids The list of card ids to be affected
     * @param imin the minimum interval (inclusive)
     * @param imax The maximum interval (inclusive)
     */
    public void reschedCards(long[] ids, int imin, int imax) {
        ArrayList<Object[]> d = new ArrayList<Object[]>();
        int t = mToday;
        long mod = Utils.intNow();
        Random rnd = new Random();
        for (long id : ids) {
            int r = rnd.nextInt(imax - imin + 1) + imin;
            d.add(new Object[] { Math.max(1, r), r + t, mCol.usn(), mod, 2500, id });
        }
        remFromDyn(ids);
        mCol.getDb().executeMany(
                "update cards set type=2,queue=2,ivl=?,due=?,odue=0, " +
                        "usn=?,mod=?,factor=? where id=?", d);
        mCol.log(ids);
    }


    /**
     * Completely reset cards for export.
     */
    public void resetCards(Long[] ids) {
        long[] nonNew = Utils.arrayList2array(mCol.getDb().queryColumn(Long.class, String.format(Locale.US,
                "select id from cards where id in %s and (queue != 0 or type != 0)", Utils.ids2str(ids)), 0));
        mCol.getDb().execute("update cards set reps=0, lapses=0 where id in " + Utils.ids2str(nonNew));
        forgetCards(nonNew);
        mCol.log((Object[]) ids);
    }


    /**
     * Repositioning new cards **************************************************
     * *********************************************
     */

    public void sortCards(long[] cids, int start) {
        sortCards(cids, start, 1, false, false);
    }


    public void sortCards(long[] cids, int start, int step, boolean shuffle, boolean shift) {
        String scids = Utils.ids2str(cids);
        long now = Utils.intNow();
        ArrayList<Long> nids = new ArrayList<Long>();
        for (long id : cids) {
        	long nid = mCol.getDb().queryLongScalar("SELECT nid FROM cards WHERE id = " + id);
        	if (!nids.contains(nid)) {
        		nids.add(nid);
        	}
        }
        if (nids.size() == 0) {
            // no new cards
            return;
        }
        // determine nid ordering
        HashMap<Long, Long> due = new HashMap<Long, Long>();
        if (shuffle) {
            Collections.shuffle(nids);
        }
        for (int c = 0; c < nids.size(); c++) {
            due.put(nids.get(c), (long) (start + c * step));
        }
        int high = start + step * (nids.size() - 1);
        // shift?
        if (shift) {
            int low = mCol.getDb().queryScalar(
                    "SELECT min(due) FROM cards WHERE due >= " + start + " AND type = 0 AND id NOT IN " + scids);
            if (low != 0) {
                int shiftby = high - low + 1;
                mCol.getDb().execute(
                        "UPDATE cards SET mod = " + now + ", usn = " + mCol.usn() + ", due = due + " + shiftby
                                + " WHERE id NOT IN " + scids + " AND due >= " + low + " AND queue = 0");
            }
        }
        // reorder cards
        ArrayList<Object[]> d = new ArrayList<Object[]>();
        Cursor cur = null;
        try {
            cur = mCol.getDb().getDatabase()
                    .rawQuery("SELECT id, nid FROM cards WHERE type = 0 AND id IN " + scids, null);
            while (cur.moveToNext()) {
                long nid = cur.getLong(1);
                d.add(new Object[] { due.get(nid), now, mCol.usn(), cur.getLong(0) });
            }
        } finally {
            if (cur != null && !cur.isClosed()) {
                cur.close();
            }
        }
        mCol.getDb().executeMany("UPDATE cards SET due = ?, mod = ?, usn = ? WHERE id = ?", d);
    }


    public void randomizeCards(long did) {
        List<Long> cids = mCol.getDb().queryColumn(Long.class, "select id from cards where did = " + did, 0);
        sortCards(Utils.toPrimitive(cids), 1, 1, true, false);
    }


    public void orderCards(long did) {
        List<Long> cids = mCol.getDb().queryColumn(Long.class, "SELECT id FROM cards WHERE did = " + did + " ORDER BY id", 0);
        sortCards(Utils.toPrimitive(cids), 1, 1, false, false);
    }


    public void resortConf(JSONObject conf) {
        List<Long> dids = mCol.getDecks().didsForConf(conf);
        try {
            for (long did : dids) {
                if (conf.getJSONObject("new").getLong("order") == 0) {
                    randomizeCards(did);
                } else {
                    orderCards(did);
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * for post-import
     */
    public void maybeRandomizeDeck() {
        maybeRandomizeDeck(null);
    }

    public void maybeRandomizeDeck(Long did) {
        if (did == null) {
            did = mCol.getDecks().selected();
        }
        JSONObject conf = mCol.getDecks().confForDid(did);
        // in order due?
        try {
            if (conf.getJSONObject("new").getInt("order") == Consts.NEW_CARDS_RANDOM) {
                randomizeCards(did);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    /*
     * ***********************************************************
     * The methods below are not in LibAnki.
     * ***********************************************************
     */

    public boolean haveBuried(long did) {
        long odid = mCol.getDecks().selected();
        mCol.getDecks().select(did);
        boolean buried = haveBuried();
        mCol.getDecks().select(odid);
        return buried;
    }

    public void unburyCardsForDeck(long did) {
        long odid = mCol.getDecks().selected();
        mCol.getDecks().select(did);
        unburyCardsForDeck();
        mCol.getDecks().select(odid);
    }


    public String getName() {
        return mName;
    }


    public int getToday() {
        return mToday;
    }


    public void setToday(int today) {
        mToday = today;
    }


    public long getDayCutoff() {
        return mDayCutoff;
    }


    public int getReps(){
        return mReps;
    }


    public void setReps(int reps){
        mReps = reps;
    }


    /**
     * Counts
     */

    public int cardCount() {
        String dids = _deckLimit();
        return mCol.getDb().queryScalar("SELECT count() FROM cards WHERE did IN " + dids);
    }


    public int matureCount() {
        String dids = _deckLimit();
        return mCol.getDb().queryScalar("SELECT count() FROM cards WHERE type = 2 AND ivl >= 21 AND did IN " + dids);
    }


    public int eta(int[] counts) {
        return eta(counts, true);
    }


    /** estimates remaining time for learning (based on last seven days)
     * 根据之前一周的情况，估计剩下的学习次数；*/
    public int eta(int[] counts, boolean reload) {
        double revYesRate;
        double revTime;
        double lrnYesRate;
        double lrnTime;
        if (reload || mEtaCache[0] == -1) {
            Cursor cur = null;
            try {
                cur = mCol
                        .getDb()
                        .getDatabase()
                        .rawQuery(
                                "SELECT avg(CASE WHEN ease > 1 THEN 1.0 ELSE 0.0 END), avg(time) FROM revlog WHERE type = 1 AND id > "
                                        + ((mCol.getSched().getDayCutoff() - (7 * 86400)) * 1000), null);
                // case...when..then...else..end语句怎么理解呢？
                // 遇到   。。情况 。。。就 。。否则。。。说完了，
                // 在此的意思是： 这种情况： 当ease>1的时候，就给它赋值1.0， 否则赋值0.0，  然后求ease的平均值。
                // 再求avg的平均值；
                // 条件是 type=1， 并且找出7天内学过的。
                if (!cur.moveToFirst()) {
                    return -1;
                }
                revYesRate = cur.getDouble(0);
                revTime = cur.getDouble(1);

                if (!cur.isClosed()) {
                    cur.close();
                }

                cur = mCol
                        .getDb()
                        .getDatabase()
                        .rawQuery(
                                "SELECT avg(CASE WHEN ease = 3 THEN 1.0 ELSE 0.0 END), avg(time) FROM revlog WHERE type != 1 AND id > "
                                        + ((mCol.getSched().getDayCutoff() - (7 * 86400)) * 1000), null);
                // case...when..then...else..end语句怎么理解呢？
                // 遇到   。。情况 。。。就 。。否则。。。说完了，
                // 在此的意思是： 这种情况： 当ease>1的时候，就给它赋值1.0， 否则赋值0.0，  然后求ease的平均值。
                // 再求avg的平均值；
                // 条件是 type=1， 并且找出7天内学过的。
                if (!cur.moveToFirst()) {
                    return -1;
                }
                lrnYesRate = cur.getDouble(0);
                lrnTime = cur.getDouble(1);
            } finally {
                if (cur != null && !cur.isClosed()) {
                    cur.close();
                }
            }
            mEtaCache[0] = revYesRate;
            mEtaCache[1] = revTime;
            mEtaCache[2] = lrnYesRate;
            mEtaCache[3] = lrnTime;
        } else {
            revYesRate = mEtaCache[0];
            revTime = mEtaCache[1];
            lrnYesRate = mEtaCache[2];
            lrnTime = mEtaCache[3];
        }
        // rev cards
        double eta = revTime * counts[2];
        // lrn cards
        double factor = Math.min(1 / (1 - lrnYesRate), 10);
        double lrnAnswers = (counts[0] + counts[1] + counts[2] * (1 - revYesRate)) * factor;
        eta += lrnAnswers * lrnTime;
        return (int) (eta / 60000);
    }


    public void decrementCounts(Card card) {
        int type = card.getQueue();
        switch (type) {
        case 0:
            mNewCount--;
            break;
        case 1:
            mLrnCount -= card.getLeft() / 1000;
            break;
        case 2:
            mRevCount--;
            break;
        case 3:
            mLrnCount--;
            break;
        }
    }


    /**
     * Sorts a card into the lrn queue LIBANKI: not in libanki
     * // 将这张卡片按准确的顺序放入学习队列中；
     */
    private void _sortIntoLrn(long due, long id) {
        Iterator i = mLrnQueue.listIterator();
        int idx = 0;
        while (i.hasNext()) {
            if (((long[]) i.next())[0] > due) {
                break;
            } else {
                idx++;
            }
        }
        mLrnQueue.add(idx, new long[] { due, id });
    }


    public boolean leechActionSuspend(Card card) {
        JSONObject conf;
        try {
            conf = _cardConf(card).getJSONObject("lapse");
            return conf.getInt("leechAction") == 0;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    public void setContext(WeakReference<Activity> contextReference) {
        mContextReference = contextReference;
    }


    /**
     * Holds the data for a single node (row) in the deck due tree (the user-visible list
     * of decks and their counts). A node also contains a list of nodes that refer to the
     * next level of sub-decks for that particular deck (which can be an empty list).
     *
     * The names field is an array of names that build a deck name from a hierarchy (i.e., a nested
     * deck will have an entry for every level of nesting). While the python version interchanges
     * between a string and a list of strings throughout processing, we always use an array for
     * this field and use names[0] for those cases.
     */
    public class DeckDueTreeNode implements Comparable {
        public String[] names;
        public long did;
        public int depth;
        public int revCount;
        public int lrnCount;
        public int newCount;
        public List<DeckDueTreeNode> children = new ArrayList<DeckDueTreeNode>();

        public DeckDueTreeNode(String[] names, long did, int revCount, int lrnCount, int newCount) {
            this.names = names;
            this.did = did;
            this.revCount = revCount;
            this.lrnCount = lrnCount;
            this.newCount = newCount;
        }

        public DeckDueTreeNode(String name, long did, int revCount, int lrnCount, int newCount) {
            this(new String[]{name}, did, revCount, lrnCount, newCount);
        }

        public DeckDueTreeNode(String name, long did, int revCount, int lrnCount, int newCount,
                               List<DeckDueTreeNode> children) {
            this(new String[]{name}, did, revCount, lrnCount, newCount);
            this.children = children;
        }

        /**
         * Sort on the head of the node.
         */
        @Override
        public int compareTo(Object other) {
            DeckDueTreeNode rhs = (DeckDueTreeNode) other;
            // Consider each subdeck name in the ordering
            for (int i = 0; i < names.length && i < rhs.names.length; i++) {
                int cmp = names[i].compareTo(rhs.names[i]);
                if (cmp == 0) {
                    continue;
                }
                return cmp;
            }
            // If we made it this far then the arrays are of different length. The longer one should
            // always come after since it contains all of the sections of the shorter one inside it
            // (i.e., the short one is an ancestor of the longer one).
            if (rhs.names.length > names.length) {
                return -1;
            } else {
                return 1;
            }
        }

        @Override
        public String toString() {
            return String.format("%s, %d, %d, %d, %d, %d, %s",
                    Arrays.toString(names), did, depth, revCount, lrnCount, newCount, children);
        }
    }
}
