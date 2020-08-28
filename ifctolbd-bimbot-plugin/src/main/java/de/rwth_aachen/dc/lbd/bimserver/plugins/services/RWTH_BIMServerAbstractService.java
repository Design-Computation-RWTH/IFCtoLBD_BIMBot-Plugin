package de.rwth_aachen.dc.lbd.bimserver.plugins.services;


import java.util.Date;

import org.bimserver.interfaces.objects.SActionState;
import org.bimserver.interfaces.objects.SInternalServicePluginConfiguration;
import org.bimserver.interfaces.objects.SLongActionState;
import org.bimserver.interfaces.objects.SObjectType;
import org.bimserver.interfaces.objects.SProgressTopicType;
import org.bimserver.models.log.AccessMethod;
import org.bimserver.models.store.ObjectDefinition;
import org.bimserver.models.store.ServiceDescriptor;
import org.bimserver.models.store.StoreFactory;
import org.bimserver.models.store.Trigger;
import org.bimserver.plugins.PluginConfiguration;
import org.bimserver.plugins.PluginContext;
import org.bimserver.plugins.services.BimServerClientInterface;
import org.bimserver.plugins.services.NewRevisionHandler;
import org.bimserver.plugins.services.ServicePlugin;
import org.bimserver.shared.exceptions.BimServerClientException;
import org.bimserver.shared.exceptions.PluginException;
import org.bimserver.shared.exceptions.PublicInterfaceNotFoundException;
import org.bimserver.shared.exceptions.ServerException;
import org.bimserver.shared.exceptions.UserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


//Modified version of the original org.bimserver.plugins.services.AbstractService 
//Modified by Jyrki Oraskari, 2020


public abstract class RWTH_BIMServerAbstractService extends ServicePlugin {
	public enum ProgressType {
		UNKNOWN,
		KNOWN
	}
	private static final Logger LOGGER = LoggerFactory.getLogger(RWTH_BIMServerAbstractService.class);
	private PluginContext pluginContext;
	private String name;

	public RWTH_BIMServerAbstractService() {
	}
	
	@Override
	public void init(PluginContext pluginContext, PluginConfiguration systemSettings) throws PluginException {
		super.init(pluginContext, systemSettings);
		this.pluginContext = pluginContext;
	}

	public PluginContext getPluginContext() {
		return pluginContext;
	}
	
	@Override
	public ObjectDefinition getUserSettingsDefinition() {
		return null;
	}

	
	/**
	 * This method gets called when there is a new revision
	 * 
	 * @param runningService A reference to the RunningService, you can use it to update the progress if you know it
	 * @param bimServerClientInterface A client with the proper authorization on this or a remote BIMserver to fetch the revision, and write extended data to
	 * @param poid ProjectID of the project
	 * @param roid RevisionID of the new revision
	 * @param userToken Optional token, unused at the moment
	 * @param soid ServiceID
	 * @param settings Optional settings a user might have given in the InternalService settings
	 * @throws Exception 
	 */
	public abstract void newRevision(RunningService runningService, BimServerClientInterface bimServerClientInterface, long poid, long roid, String userToken, long soid, SObjectType settings) throws Exception;

	/**
	 * Should return whether this service can report progress (as a percentage) or not
	 * @return ProgressType.UNKNOWN when the progress is not known, or KNOWN when it is
	 */
	public ProgressType getProgressType() {
		return ProgressType.UNKNOWN;
	}

	public class RunningService {
		private long topicId;
		private BimServerClientInterface bimServerClientInterface;
		private Date startDate;
		private PluginConfiguration pluginConfiguration;
		private String currentUser;

		public RunningService(long topicId, BimServerClientInterface bimServerClientInterface, PluginConfiguration pluginConfiguration, String currentUser) {
			this.pluginConfiguration = pluginConfiguration;
			this.currentUser = currentUser;
			this.startDate = new Date();
			this.topicId = topicId;
			this.bimServerClientInterface = bimServerClientInterface;
		}
		
		public PluginConfiguration getPluginConfiguration() {
			return pluginConfiguration;
		}

		public Date getStartDate() {
			return startDate;
		}
		
		/**
		 * Update progress
		 * @param progress Between 0 and 100 inclusive
		 */
		public void updateProgress(int progress) {
			SLongActionState state = new SLongActionState();
			state.setProgress(progress);
			state.setTitle(name);
			state.setState(SActionState.FINISHED);
			state.setStart(startDate);
			state.setEnd(new Date());
			try {
				bimServerClientInterface.getRegistry().updateProgressTopic(topicId, state);
			} catch (UserException e) {
				LOGGER.error("", e);
			} catch (ServerException e) {
				LOGGER.error("", e);
			} catch (PublicInterfaceNotFoundException e) {
				LOGGER.error("", e);
			}
		}

		public String getCurrentUser() {
			return currentUser;
		}
	}
	
	public abstract void addRequiredRights(ServiceDescriptor serviceDescriptor);
	
	@Override
	public void register(long uoid, SInternalServicePluginConfiguration internalService, final PluginConfiguration pluginConfiguration) {
		name = internalService.getName();
		ServiceDescriptor serviceDescriptor = StoreFactory.eINSTANCE.createServiceDescriptor();
		serviceDescriptor.setProviderName("RWTH");
		serviceDescriptor.setIdentifier("" + internalService.getOid());
		serviceDescriptor.setName(internalService.getName());
		serviceDescriptor.setDescription(internalService.getDescription());
		serviceDescriptor.setNotificationProtocol(AccessMethod.INTERNAL);
		serviceDescriptor.setTrigger(Trigger.NEW_REVISION);
		addRequiredRights(serviceDescriptor);
		serviceDescriptor.setReadRevision(true);
		registerNewRevisionHandler(uoid, serviceDescriptor, new NewRevisionHandler() {
			@Override
			public void newRevision(BimServerClientInterface bimServerClientInterface, long poid, long roid, String userToken, long soid, SObjectType settings) throws ServerException, UserException {
				try {
					Long topicId = bimServerClientInterface.getRegistry().registerProgressOnRevisionTopic(SProgressTopicType.RUNNING_SERVICE, poid, roid, "Running " + name);
					RunningService runningService = new RunningService(topicId, bimServerClientInterface, pluginConfiguration, bimServerClientInterface.getAuthInterface().getLoggedInUser().getUsername());
					try {
						SLongActionState state = new SLongActionState();
						state.setProgress(getProgressType() == ProgressType.KNOWN ? 0 : -1);
						state.setTitle(name);
						state.setState(SActionState.STARTED);
						state.setStart(runningService.getStartDate());
						bimServerClientInterface.getRegistry().updateProgressTopic(topicId, state);
						
						RWTH_BIMServerAbstractService.this.newRevision(runningService, bimServerClientInterface, poid, roid, userToken, soid, settings);
						
						state = new SLongActionState();
						state.setProgress(100);
						state.setTitle(name);
						state.setState(SActionState.FINISHED);
						state.setStart(runningService.getStartDate());
						state.setEnd(new Date());
						bimServerClientInterface.getRegistry().updateProgressTopic(topicId, state);
					} catch (BimServerClientException e) {
						LOGGER.error("", e);
					} catch (Exception e) {
						LOGGER.error("", e);
					} finally {
						bimServerClientInterface.getRegistry().unregisterProgressTopic(topicId);
					}
				} catch (PublicInterfaceNotFoundException e) {
					LOGGER.error("", e);
				}
			}
		});
	}

	@Override
	public void unregister(SInternalServicePluginConfiguration internalService) {
		// TODO
	}
}