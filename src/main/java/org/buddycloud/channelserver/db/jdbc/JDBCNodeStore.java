package org.buddycloud.channelserver.db.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.buddycloud.channelserver.db.ClosableIteratorImpl;
import org.buddycloud.channelserver.db.CloseableIterator;
import org.buddycloud.channelserver.db.NodeStore;
import org.buddycloud.channelserver.db.exception.ItemNotFoundException;
import org.buddycloud.channelserver.db.exception.NodeStoreException;
import org.buddycloud.channelserver.pubsub.affiliation.Affiliations;
import org.buddycloud.channelserver.pubsub.model.NodeAffiliation;
import org.buddycloud.channelserver.pubsub.model.NodeItem;
import org.buddycloud.channelserver.pubsub.model.NodeSubscription;
import org.buddycloud.channelserver.pubsub.model.impl.NodeAffiliationImpl;
import org.buddycloud.channelserver.pubsub.model.impl.NodeItemImpl;
import org.buddycloud.channelserver.pubsub.model.impl.NodeSubscriptionImpl;
import org.buddycloud.channelserver.pubsub.subscription.Subscriptions;
import org.xmpp.packet.JID;
import org.xmpp.resultsetmanagement.ResultSet;
import org.xmpp.resultsetmanagement.ResultSetImpl;

public class JDBCNodeStore implements NodeStore {
	private Logger logger = Logger.getLogger(JDBCNodeStore.class);
	private final Connection conn;
	private final NodeStoreSQLDialect dialect;
	private final Deque<JDBCTransaction> transactionStack;
	private boolean transactionHasBeenRolledBack = false;

	/**
	 * Create a new node store connection backed by the given JDBC
	 * {@link Connection}.
	 * 
	 * @param conn
	 *            the connection to the backing database.
	 */
	public JDBCNodeStore(final Connection conn,
			final NodeStoreSQLDialect dialect) {
		this.conn = conn;
		this.dialect = dialect;
		transactionStack = new ArrayDeque<JDBCTransaction>();
	}

	@Override
	public void createNode(JID owner, String nodeId,
			Map<String, String> nodeConf) throws NodeStoreException {
		if (owner == null)
			throw new NullPointerException("owner must not be null");
		if (nodeId == null)
			throw new NullPointerException("nodeId must not be null");

		PreparedStatement addStatement = null;
		try {
			// Store node
			addStatement = conn.prepareStatement(dialect.insertNode());
			addStatement.setString(1, nodeId);
			addStatement.executeUpdate();
			addStatement.close();

			// Store the config (if there is any)
			if (nodeConf != null) {
				setNodeConf(nodeId, nodeConf);
			}
			NodeSubscriptionImpl subscription = new NodeSubscriptionImpl(
					nodeId, owner, Subscriptions.subscribed);
			addUserSubscription(subscription);
			setUserAffiliation(nodeId, owner, Affiliations.owner);
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(addStatement);
		}
	}

	@Override
	public void addRemoteNode(String nodeId) throws NodeStoreException {
		if (null == nodeId) {
			throw new NullPointerException("nodeId must not be null");
		}
		PreparedStatement addStatement = null;
		try {
			addStatement = conn.prepareStatement(dialect.insertNode());
			addStatement.setString(1, nodeId);
			addStatement.executeUpdate();
			addStatement.close();
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(addStatement);
		}
	}

	@Override
	public void setNodeConfValue(String nodeId, String key, String value)
			throws NodeStoreException {
		if (nodeId == null)
			throw new NullPointerException("nodeId must not be null");
		if (key == null)
			throw new NullPointerException("key must not be null");

		PreparedStatement updateStatement = null;
		PreparedStatement addStatement = null;

		try {
			updateStatement = conn.prepareStatement(dialect.updateNodeConf());
			updateStatement.setString(1, value);
			updateStatement.setString(2, nodeId);
			updateStatement.setString(3, key);
			int rows = updateStatement.executeUpdate();
			updateStatement.close();

			if (rows == 0) { // If the update didn't update any rows
				addStatement = conn.prepareStatement(dialect.insertConf());
				addStatement.setString(1, nodeId);
				addStatement.setString(2, key);
				addStatement.setString(3, value);

				addStatement.executeUpdate();
			}
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(updateStatement);
			close(addStatement);
		}
	}

	@Override
	public void setNodeConf(String nodeId, Map<String, String> conf)
			throws NodeStoreException {
		Transaction t = null;
		PreparedStatement stmt = null;

		try {
			t = beginTransaction();

			stmt = conn.prepareStatement(dialect.deleteConfFromNode());
			stmt.setString(1, nodeId);
			stmt.executeUpdate();
			stmt.close();

			for (final Entry<String, String> entry : conf.entrySet()) {
				setNodeConfValue(nodeId, entry.getKey(), entry.getValue());
			}
			t.commit();
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt);
			close(t);
		}
	}

	@Override
	public boolean nodeExists(String nodeId) throws NodeStoreException {
		PreparedStatement existsStatement = null;
		try {
			existsStatement = conn.prepareStatement(dialect.nodeExists());
			existsStatement.setString(1, nodeId);
			java.sql.ResultSet rs = existsStatement.executeQuery();

			boolean exists = rs.next();

			rs.close();
			existsStatement.close();

			return exists;
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(existsStatement); // Will implicitly close the resultset if
									// required
		}
	}

	@Override
	public void setUserAffiliation(String nodeId, JID user,
			Affiliations affiliation) throws NodeStoreException {
		PreparedStatement deleteStatement = null;
		PreparedStatement updateStatement = null;
		PreparedStatement addStatement = null;
		Transaction t = null;

		try {
			t = beginTransaction();
			if (affiliation.equals(Affiliations.none)) {
				deleteStatement = conn.prepareStatement(dialect
						.deleteAffiliation());
				deleteStatement.setString(1, nodeId);
				deleteStatement.setString(2, user.toBareJID());
				int rows = deleteStatement.executeUpdate();
				deleteStatement.close();
			} else {
				updateStatement = conn.prepareStatement(dialect
						.updateAffiliation());
				updateStatement.setString(1, affiliation.toString());
				updateStatement.setString(2, nodeId);
				updateStatement.setString(3, user.toBareJID());
				int rows = updateStatement.executeUpdate();
				updateStatement.close();

				if (rows == 0) { // If the update didn't update any rows
					addStatement = conn.prepareStatement(dialect
							.insertAffiliation());
					addStatement.setString(1, nodeId);
					addStatement.setString(2, user.toBareJID());
					addStatement.setString(3, affiliation.toString());
					addStatement.executeUpdate();
					addStatement.close();
				}
			}
			t.commit();
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(deleteStatement);
			close(updateStatement);
			close(addStatement);
			close(t);
		}
	}

	@Override
	public String getNodeConfValue(String nodeId, String key)
			throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect.selectSingleNodeConfValue());

			stmt.setString(1, nodeId);
			stmt.setString(2, key);

			java.sql.ResultSet rs = stmt.executeQuery();

			String result = null;

			if (rs.next()) {
				result = rs.getString(1);
			}

			return result;
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public Map<String, String> getNodeConf(String nodeId)
			throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect.selectNodeConf());

			stmt.setString(1, nodeId);

			java.sql.ResultSet rs = stmt.executeQuery();

			HashMap<String, String> result = new HashMap<String, String>();

			while (rs.next()) {
				result.put(rs.getString(1), rs.getString(2));
			}

			return result;
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public void addUserSubscription(final NodeSubscription subscription)
			throws NodeStoreException {
		PreparedStatement deleteStatement = null;
		PreparedStatement updateStatement = null;
		PreparedStatement addStatement = null;
		Transaction t = null;

		try {
			if (subscription.getSubscription().equals(Subscriptions.none)) {
				deleteStatement = conn.prepareStatement(dialect
						.deleteSubscription());
				deleteStatement.setString(1, subscription.getNodeId());
				deleteStatement
						.setString(2, subscription.getUser().toBareJID());
				deleteStatement.executeUpdate();
				deleteStatement.close();
			} else {
				t = beginTransaction();
				updateStatement = conn.prepareStatement(dialect
						.updateSubscription());
				updateStatement.setString(1, subscription.getSubscription()
						.toString());
				updateStatement.setString(2, subscription.getListener()
						.toString());
				updateStatement.setString(3, subscription.getNodeId());
				updateStatement
						.setString(4, subscription.getUser().toBareJID());

				int rows = updateStatement.executeUpdate();
				updateStatement.close();

				if (rows == 0) { // If the update didn't update any rows
					addStatement = conn.prepareStatement(dialect
							.insertSubscription());
					addStatement.setString(1, subscription.getNodeId());
					addStatement.setString(2, subscription.getUser()
							.toBareJID());
					addStatement.setString(3, subscription.getListener()
							.toString());
					addStatement.setString(4, subscription.getSubscription()
							.toString());
					addStatement.executeUpdate();
					addStatement.close();
				}
				t.commit();
			}
		} catch (SQLException e) {
			logger.debug("Error adding new subscription: " + e.getMessage());
			throw new NodeStoreException(e);
		} finally {
			close(deleteStatement);
			close(updateStatement);
			close(addStatement);
			close(t);
		}
	}

	@Override
	public NodeAffiliation getUserAffiliation(String nodeId, JID user)
			throws NodeStoreException {
		PreparedStatement selectStatement = null;

		try {
			NodeAffiliationImpl affiliation;

			selectStatement = conn
					.prepareStatement(dialect.selectAffiliation());
			selectStatement.setString(1, nodeId);
			selectStatement.setString(2, user.toBareJID());

			java.sql.ResultSet rs = selectStatement.executeQuery();

			if (rs.next()) {
				affiliation = new NodeAffiliationImpl(nodeId, user,
						Affiliations.valueOf(rs.getString(1)));
			} else {
				affiliation = new NodeAffiliationImpl(nodeId, user,
						Affiliations.none);
			}

			return affiliation;
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(selectStatement); // Will implicitly close the resultset if
									// required
		}
	}

	@Override
	public ResultSet<NodeAffiliation> getUserAffiliations(JID user)
			throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect.selectAffiliationsForUser());
			stmt.setString(1, user.toBareJID());

			java.sql.ResultSet rs = stmt.executeQuery();

			ArrayList<NodeAffiliation> result = new ArrayList<NodeAffiliation>();

			while (rs.next()) {
				NodeAffiliationImpl nodeSub = new NodeAffiliationImpl(
						rs.getString(1), user, Affiliations.valueOf(rs
								.getString(3)));
				result.add(nodeSub);
			}

			return new ResultSetImpl<NodeAffiliation>(result);
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public ResultSet<NodeAffiliation> getNodeAffiliations(String nodeId)
			throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect.selectAffiliationsForNode());
			stmt.setString(1, nodeId);

			java.sql.ResultSet rs = stmt.executeQuery();

			ArrayList<NodeAffiliation> result = new ArrayList<NodeAffiliation>();

			while (rs.next()) {
				NodeAffiliationImpl nodeSub = new NodeAffiliationImpl(
						rs.getString(1), new JID(rs.getString(2)),
						Affiliations.valueOf(rs.getString(3)));
				result.add(nodeSub);
			}

			return new ResultSetImpl<NodeAffiliation>(result);
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public NodeSubscription getUserSubscription(String nodeId, JID user)
			throws NodeStoreException {
		PreparedStatement selectStatement = null;

		try {
			NodeSubscriptionImpl subscription;

			selectStatement = conn.prepareStatement(dialect
					.selectSubscription());
			selectStatement.setString(1, nodeId);
			selectStatement.setString(2, user.toBareJID());
			if ((null == user.getNode()) || (true == user.getNode().isEmpty())) {
				selectStatement.setString(3, user.getDomain());
			} else {
			    selectStatement.setString(3, user.toString());
			}
			java.sql.ResultSet rs = selectStatement.executeQuery();

			if (rs.next()) {
				subscription = new NodeSubscriptionImpl(nodeId, new JID(
						rs.getString(2)), new JID(rs.getString(3)),
						Subscriptions.valueOf(rs.getString(4)));
			} else {
				subscription = new NodeSubscriptionImpl(nodeId, user, user,
						Subscriptions.none);
			}

			return subscription;
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(selectStatement); // Will implicitly close the resultset if
									// required
		}
	}

	@Override
	public ResultSet<NodeSubscription> getUserSubscriptions(final JID user)
			throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect.selectSubscriptionsForUser());
			stmt.setString(1, user.toBareJID());
			stmt.setString(2, user.toString());

			java.sql.ResultSet rs = stmt.executeQuery();

			ArrayList<NodeSubscription> result = new ArrayList<NodeSubscription>();

			while (rs.next()) {
				NodeSubscriptionImpl nodeSub = new NodeSubscriptionImpl(
						rs.getString(1), new JID(rs.getString(2)), new JID(
								rs.getString(3)), Subscriptions.valueOf(rs
								.getString(4)));
				result.add(nodeSub);
			}

			return new ResultSetImpl<NodeSubscription>(result);
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public ResultSet<NodeSubscription> getNodeSubscriptions(String nodeId)
			throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect.selectSubscriptionsForNode());
			stmt.setString(1, nodeId);

			java.sql.ResultSet rs = stmt.executeQuery();

			ArrayList<NodeSubscription> result = new ArrayList<NodeSubscription>();

			while (rs.next()) {
				NodeSubscriptionImpl nodeSub = new NodeSubscriptionImpl(
						rs.getString(1), new JID(rs.getString(2)), new JID(
								rs.getString(3)), Subscriptions.valueOf(rs
								.getString(4)));
				result.add(nodeSub);
			}

			return new ResultSetImpl<NodeSubscription>(result);
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public ResultSet<NodeSubscription> getNodeSubscriptionListeners(
			String nodeId) throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect
					.selectSubscriptionListenersForNode());
			stmt.setString(1, nodeId);

			java.sql.ResultSet rs = stmt.executeQuery();

			ArrayList<NodeSubscription> result = new ArrayList<NodeSubscription>();

			while (rs.next()) {
				NodeSubscriptionImpl nodeSub = new NodeSubscriptionImpl(
						rs.getString(1), new JID(rs.getString(2)),
						Subscriptions.valueOf(rs.getString(3)));
				result.add(nodeSub);
			}

			return new ResultSetImpl<NodeSubscription>(result);
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public CloseableIterator<NodeItem> getNodeItems(String nodeId,
			String afterItemId, int count) throws NodeStoreException {
		NodeItem afterItem = null;

		PreparedStatement stmt = null;

		if (afterItemId != null) {
			afterItem = getNodeItem(nodeId, afterItemId);
		}

		String countSQL = "";

		if (count > -1) {
			countSQL = " OFFSET 0 LIMIT " + count;
		} else if (count < -1) {
			throw new IllegalArgumentException(
					"Invalid value for parameter count: " + count);
		}

		try {
			if (afterItem == null) {
				stmt = conn.prepareStatement(dialect.selectItemsForNode()
						+ countSQL);
				stmt.setString(1, nodeId);

				java.sql.ResultSet rs = stmt.executeQuery();

				stmt = null; // Prevent the finally block from closing the
								// statement

				return new ResultSetIterator<NodeItem>(rs,
						new ResultSetIterator.RowConverter<NodeItem>() {
							@Override
							public NodeItem convertRow(java.sql.ResultSet rs)
									throws SQLException {
								return new NodeItemImpl(rs.getString(1),
										rs.getString(2), rs.getTimestamp(3),
										rs.getString(4));
							}
						});
			} else {
				stmt = conn.prepareStatement(dialect
						.selectItemsForNodeAfterDate() + countSQL);
				stmt.setString(1, nodeId);
				stmt.setDate(2, new java.sql.Date(afterItem.getUpdated()
						.getTime()));
				stmt.setDate(3, new java.sql.Date(afterItem.getUpdated()
						.getTime()));
				stmt.setString(4, afterItemId);

				java.sql.ResultSet rs = stmt.executeQuery();

				LinkedList<NodeItem> results = new LinkedList<NodeItem>();

				while (rs.next()) {
					results.push(new NodeItemImpl(rs.getString(1), rs
							.getString(2), rs.getTimestamp(3), rs.getString(4)));
				}

				return new ClosableIteratorImpl<NodeItem>(results.iterator());
			}
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public CloseableIterator<NodeItem> getNodeItems(String nodeId)
			throws NodeStoreException {
		return getNodeItems(nodeId, null, -1);
	}

	@Override
	public int countNodeItems(String nodeId) throws NodeStoreException {
		PreparedStatement selectStatement = null;

		try {
			selectStatement = conn
					.prepareStatement(dialect.countItemsForNode());
			selectStatement.setString(1, nodeId);

			java.sql.ResultSet rs = selectStatement.executeQuery();

			if (rs.next()) {
				return rs.getInt(1);
			} else {
				return 0; // This really shouldn't happen!
			}
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(selectStatement); // Will implicitly close the resultset if
									// required
		}
	}

	@Override
	public NodeItem getNodeItem(String nodeId, String nodeItemId)
			throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect.selectSingleItem());

			stmt.setString(1, nodeId);
			stmt.setString(2, nodeItemId);

			java.sql.ResultSet rs = stmt.executeQuery();

			if (rs.next()) {
				return new NodeItemImpl(rs.getString(1), rs.getString(2),
						rs.getTimestamp(3), rs.getString(4));
			}

			return null;
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public void addNodeItem(NodeItem item) throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect.insertItem());

			stmt.setString(1, item.getNodeId());
			stmt.setString(2, item.getId());
			stmt.setTimestamp(3, new Timestamp(item.getUpdated().getTime()));
			stmt.setString(4, item.getPayload());

			stmt.executeUpdate();
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public void updateNodeItem(NodeItem item) throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect.updateItem());

			stmt.setTimestamp(1, new Timestamp(item.getUpdated().getTime()));
			stmt.setString(2, item.getPayload());
			stmt.setString(3, item.getNodeId());
			stmt.setString(4, item.getId());

			int rows = stmt.executeUpdate();

			if (rows != 1) {
				throw new ItemNotFoundException(
						"No records affected when updating an item");
			}
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public void deleteNodeItemById(String nodeId, String nodeItemId)
			throws NodeStoreException {
		PreparedStatement stmt = null;

		try {
			stmt = conn.prepareStatement(dialect.deleteItem());

			stmt.setString(1, nodeId);
			stmt.setString(2, nodeItemId);

			int rows = stmt.executeUpdate();

			if (rows != 1) {
				throw new ItemNotFoundException(
						"No records affected when deleting an item");
			}
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		} finally {
			close(stmt); // Will implicitly close the resultset if required
		}
	}

	@Override
	public Transaction beginTransaction() throws NodeStoreException {
		if (transactionHasBeenRolledBack) {
			throw new IllegalStateException(
					"The transaction has already been rolled back");
		}

		JDBCTransaction transaction;
		try {
			transaction = new JDBCTransaction(this);
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		}
		return transaction;
	}

	private void close(final Statement stmt) {
		if (stmt != null) {
			try {
				if (false == stmt.isClosed())
					stmt.close();
				// stmt.getConnection().close();
			} catch (SQLException e) {
				logger.error(
						"SQLException thrown while trying to close a statement",
						e);
			}
		}
	}

	private void close(final Transaction trans) throws NodeStoreException {
		if (trans != null) {
			trans.close();
		}
	}

	public class JDBCTransaction implements Transaction {
		private JDBCNodeStore store;
		private boolean closed;

		private JDBCTransaction(final JDBCNodeStore store) throws SQLException {
			this.store = store;
			closed = false;

			if (store.transactionStack.isEmpty()) {
				store.conn.setAutoCommit(false);
			}

			store.transactionStack.push(this);
		}

		@Override
		public void commit() throws NodeStoreException {
			if (closed) {
				throw new IllegalStateException(
						"Commit called on transaction that is already closed");
			}
			if (!isLatestTransaction()) {
				throw new IllegalStateException(
						"Commit called on transaction other than the innermost transaction");
			}
			if (store.transactionHasBeenRolledBack) {
				throw new IllegalStateException(
						"Commit called after inner transaction has already been rolled back");
			}

			store.transactionStack.pop();
			closed = true;

			try {
				if (store.transactionStack.isEmpty()) {
					store.conn.commit();
					store.conn.setAutoCommit(true);
					store.transactionHasBeenRolledBack = false;
				}
			} catch (SQLException e) {
				throw new NodeStoreException(e);
			}
		}

		@Override
		public void close() throws NodeStoreException {
			if (closed) {
				return; // Do nothing nicely and silently
			}

			if (!isLatestTransaction()) {
				throw new IllegalStateException(
						"Close called on transaction other than the innermost transaction");
			}

			store.transactionStack.pop();
			closed = true;
			store.transactionHasBeenRolledBack = true;

			try {
				if (store.transactionStack.isEmpty()) {
					store.conn.rollback();
					store.conn.setAutoCommit(true);
					store.transactionHasBeenRolledBack = false;
				}
			} catch (SQLException e) {
				throw new NodeStoreException(e);
			}
		}

		private boolean isLatestTransaction() {
			return (store.transactionStack.peek() == this);
		}
	}

	@Override
	public void close() throws NodeStoreException {
		try {
			conn.close();
		} catch (SQLException e) {
			throw new NodeStoreException(e);
		}
	}

	public interface NodeStoreSQLDialect {
		String insertNode();

		String insertConf();

		String deleteConfFromNode();

		String updateNodeConf();

		String selectSingleNodeConfValue();

		String selectNodeConf();

		String selectAffiliation();

		String selectAffiliationsForUser();

		String selectAffiliationsForNode();

		String insertAffiliation();

		String updateAffiliation();

		String deleteAffiliation();

		String selectSubscription();

		String selectSubscriptionsForUser();

		String selectSubscriptionsForNode();

		String selectSubscriptionListenersForNode();

		String insertSubscription();

		String updateSubscription();

		String deleteSubscription();

		String nodeExists();

		String selectSingleItem();

		String selectItemsForNode();

		String selectItemsForNodeAfterDate();

		String countItemsForNode();

		String insertItem();

		String updateItem();

		String deleteItem();
	}
}
