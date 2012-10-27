package teammates.ui.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import teammates.common.Common;
import teammates.common.datatransfer.CoordData;
import teammates.common.datatransfer.CourseData;
import teammates.common.datatransfer.EvaluationData;
import teammates.common.datatransfer.StudentData;
import teammates.common.datatransfer.SubmissionData;
import teammates.common.datatransfer.TeamData;
import teammates.common.datatransfer.UserData;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.exception.UnauthorizedAccessException;
import teammates.logic.api.Logic;

@SuppressWarnings("serial")
/**
 * Abstract servlet to handle actions.
 * Child class must implement all the abstract methods,
 * which will be called from the doPost method in the superclass.
 * This is template pattern as said in:
 * http://stackoverflow.com/questions/7350297/good-method-to-make-it-obvious-that-an-overriden-method-should-call-super
 */
public abstract class ActionServlet<T extends Helper> extends HttpServlet {

	protected static final Logger log = Common.getLogger();
	protected boolean isPost = false;

	@Override
	public final void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws IOException, ServletException {

		this.doPost(req, resp);
	}

	@Override
	public final void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws IOException, ServletException {

		isPost = req.getMethod().equals("POST");
		if (isPost) {
			log.info("POST");
		} else {
			log.info("GET");
		}

		T helper = instantiateHelper();

		prepareHelper(req, helper);
		Level logLevel = null;
		String response = null;
		try {
			doAction(req, helper);
			logLevel = Level.INFO;
			response = "OK";
			doCreateResponse(req, resp, helper);

		} catch (EntityDoesNotExistException e) {
			logLevel = Level.WARNING;
			response = "EntityDoesNotExistException";
			resp.sendRedirect(Common.JSP_ENTITY_NOT_FOUND_PAGE);
		} catch (UnauthorizedAccessException e) {
			UserData user = new Logic().getLoggedInUser();
			logLevel = Level.WARNING;
			response = "Unauthorized access attempted by:"
					+ (user == null ? "not-logged-user" : user.id);
			resp.sendRedirect(Common.JSP_UNAUTHORIZED);
		} catch (Throwable e) {
			logLevel = Level.SEVERE;
			response = "Unexpected exception:"
					+ (e.getMessage() == null ? "" : e.getMessage());
		
			resp.sendRedirect(Common.JSP_ERROR_PAGE);
		} finally {
			//log activity
			String logMsg = getUserActionLog(req, response, helper);
			logUserAction(logLevel, logMsg);
			
		}
	}
	
	protected void logUserAction(Level logLevel, String logMsg) {
		if(logLevel.equals(Level.INFO)) {
			log.info(logMsg);
		}else if(logLevel.equals(Level.WARNING)) {
			log.warning(logMsg);
		}else if(logLevel.equals(Level.SEVERE)) {
			log.severe(logMsg);
		}else {
			log.severe("Unknown Log Level" + logLevel.toString() + "|"+logMsg);
		}
	}
	
	protected String getUserActionLog(HttpServletRequest req, String resp, T helper) {
		
		//Assumption.assertFalse("admin activity shouldn't be logged",helper.user.isAdmin);
		StringBuilder sb = new StringBuilder("[TEAMMATES_LOG]|");
		//log action
		String[] actionTkn = req.getServletPath().split("/");
		String action = req.getServletPath();
		if(actionTkn.length > 0) {
			action = actionTkn[actionTkn.length-1]; //retrieve last segment in path
		}
		sb.append(action+"|");

		//log user information
		if(helper.user.isCoord) {
			sb.append("Coordinator|");
			CoordData u = helper.server.getCoord(helper.userId);
			sb.append(u.name+"|");
			sb.append(u.id+"|");
			sb.append(u.email+"|");
		}else if(helper.user.isStudent) {
			sb.append("Student|");
			ArrayList<StudentData> students = helper.server.getStudentsWithId(helper.userId);
			if(students.size() == 1) {
				StudentData s = students.get(0);
				sb.append(s.name+"|");
				sb.append(s.id+"|");
				sb.append(s.email+"|");
			}else {
				sb.append("Unknown User|");
				sb.append( helper.userId+"|");
				sb.append( helper.userId+"|");
			}
	       
		}else {
			sb.append("Unknown Role|");
			sb.append("Unknown User|");
			sb.append( helper.userId+"|");
			sb.append( helper.userId+"|");
		}
		
		//log response
		sb.append(resp+"|");
		sb.append(printRequestParameters(req));

		return sb.toString();
	}

	/**
	 * Prepare the helper by filling these variables:
	 * <ul>
	 * <li>servlet</li>
	 * <li>user</li>
	 * <li>requestedUser</li>
	 * <li>userId - depends on the masquerade mode</li>
	 * <li>redirectUrl - get from the request</li>
	 * <li>statusMessage - set to null</li>
	 * <li>error - set to false</li>
	 * </ul>
	 * 
	 * @param req
	 * @param helper
	 */
	private void prepareHelper(HttpServletRequest req, T helper) {
		helper.server = new Logic();
		helper.user = helper.server.getLoggedInUser();

		helper.requestedUser = req.getParameter(Common.PARAM_USER_ID);
		helper.redirectUrl = req.getParameter(Common.PARAM_NEXT_URL);

		helper.statusMessage = req.getParameter(Common.PARAM_STATUS_MESSAGE);
		helper.error = "true".equalsIgnoreCase(req
				.getParameter(Common.PARAM_ERROR));

		if (helper.isMasqueradeMode()) {
			helper.userId = helper.requestedUser;
		} else {
			helper.userId = helper.user.id;
		}
	}

	/**
	 * Method to instantiate the helper. This method will be called at the
	 * beginning of request processing.
	 * 
	 * @return
	 */
	protected abstract T instantiateHelper();


	/**
	 * Method to do all the actions for this servlet. This method is supposed to
	 * only interact with API, and not to send response, which will be done in
	 * {@link #doCreateResponse}. This method is called directly after
	 * successful {@link #doAuthenticateUser}. It may include these steps:
	 * <ul>
	 * <li>Get parameters</li>
	 * <li>Validate parameters</li>
	 * <li>Send parameters and operation to server</li>
	 * <li>Put the response from API into the helper object</li>
	 * </ul>
	 * If exception is thrown, then the servlet will redirect the client to the
	 * error page with customized error message depending on the Exception
	 * 
	 * @param req
	 * @param resp
	 * @param helper
	 * @throws InvalidParametersException 
	 * @throws Exception
	 */
	protected abstract void doAction(HttpServletRequest req, T helper)
			throws EntityDoesNotExistException, InvalidParametersException;

	/**
	 * Method to redirect or forward the request to appropriate display handler.<br />
	 * If helper.redirectUrl is not null, it will redirect there. Otherwise it
	 * will forward the request to helper.forwardUrl. If the link to be
	 * forwarded is null, it will get the default link from
	 * {@link #getDefaultForwardUrl}.<br />
	 * For redirection, any status message according to the helper.statusMessage
	 * will be displayed together with the error status (helper.error) as new
	 * parameters in the redirect URL<br />
	 * This method will also append the requested user ID in case of masquerade
	 * mode by admin.<br />
	 * For forwarding, the helper object will be attached to the request as an
	 * attribute with name "helper". The forward URL is used as is without any
	 * modification.<br />
	 * This method is called directly after {@link #doAction} method.
	 * 
	 * @param req
	 * @param resp
	 * @param helper
	 * @throws ServletException
	 * @throws IOException
	 */
	protected final void doCreateResponse(HttpServletRequest req,
			HttpServletResponse resp, T helper) throws ServletException,
			IOException {
		if (helper.redirectUrl != null) {
			helper.redirectUrl = Common.addParamToUrl(helper.redirectUrl,
					Common.PARAM_STATUS_MESSAGE, helper.statusMessage);
			if (helper.error) {
				helper.redirectUrl = Common.addParamToUrl(helper.redirectUrl,
						Common.PARAM_ERROR, "" + helper.error);
			}
			helper.redirectUrl = helper.processMasquerade(helper.redirectUrl);
			resp.sendRedirect(helper.redirectUrl);
		} else {
			if (helper.forwardUrl == null) {
				helper.forwardUrl = getDefaultForwardUrl();
			}
			req.setAttribute("helper", helper);
			req.getRequestDispatcher(helper.forwardUrl).forward(req, resp);
		}
	}

	/**
	 * Returns the default link to forward to in case the redirectUrl is null<br />
	 * This is usually the display URL, which is a link to real JSP file used to
	 * display the result of the actions performed in this servlet.
	 * 
	 * @return
	 */
	protected String getDefaultForwardUrl() {
		// Not used
		return "";
	}

	/**
	 * Returns the URL used to call this servlet. Reminder: This URL cannot be
	 * used to repeat sending POST data
	 * 
	 * @param req
	 * @return
	 */
	protected String getRequestedURL(HttpServletRequest req) {
		String link = req.getRequestURI();
		String query = req.getQueryString();
		if (query != null)
			link += "?" + query;
		return link;
	}

	/**
	 * Sorts courses based on course ID
	 * 
	 * @param courses
	 */
	protected void sortCourses(List<CourseData> courses) {
		Collections.sort(courses, new Comparator<CourseData>() {
			public int compare(CourseData obj1, CourseData obj2) {
				return obj1.id.compareTo(obj2.id);
			}
		});
	}

	/**
	 * Sorts evaluations based courseID (ascending), then by deadline
	 * (ascending), then by start time (ascending), then by evaluation name
	 * (ascending) The sort by CourseID part is to cater the case when this
	 * method is called with combined evaluations from many courses
	 * 
	 * @param evals
	 */
	protected void sortEvaluationsByDeadline(List<EvaluationData> evals) {
		Collections.sort(evals, new Comparator<EvaluationData>() {
			public int compare(EvaluationData obj1, EvaluationData obj2) {
				int result = 0;
				if (result == 0)
					result = obj1.endTime.after(obj2.endTime) ? 1
							: (obj1.endTime.before(obj2.endTime) ? -1 : 0);
				if (result == 0)
					result = obj1.startTime.after(obj2.startTime) ? 1
							: (obj1.startTime.before(obj2.startTime) ? -1 : 0);
				if (result == 0)
					result = obj1.course.compareTo(obj2.course);
				if (result == 0)
					result = obj1.name.compareTo(obj2.name);
				return result;
			}
		});
	}

	/**
	 * Sorts teams based on team name
	 * 
	 * @param teams
	 */
	protected void sortTeams(List<TeamData> teams) {
		Collections.sort(teams, new Comparator<TeamData>() {
			public int compare(TeamData s1, TeamData s2) {
				return (s1.name).compareTo(s2.name);
			}
		});
	}

	/**
	 * Sorts students based on student name then by email
	 * 
	 * @param students
	 */
	protected void sortStudents(List<StudentData> students) {
		Collections.sort(students, new Comparator<StudentData>() {
			public int compare(StudentData s1, StudentData s2) {
				int result = s1.name.compareTo(s2.name);
				if (result == 0)
					result = s1.email.compareTo(s2.email);
				return result;
			}
		});
	}

	/**
	 * Sorts submissions based on feedback (the first 70 chars)
	 * 
	 * @param submissions
	 */
	protected void sortSubmissionsByFeedback(List<SubmissionData> submissions) {
		Collections.sort(submissions, new Comparator<SubmissionData>() {
			public int compare(SubmissionData s1, SubmissionData s2) {
				return s1.p2pFeedback.toString().compareTo(
						s2.p2pFeedback.toString());
			}
		});
	}
	
	/**
	 * Sorts submissions based on justification (the first 70 chars)
	 * 
	 * @param submissions
	 */
	protected void sortSubmissionsByJustification(List<SubmissionData> submissions) {
		Collections.sort(submissions, new Comparator<SubmissionData>() {
			public int compare(SubmissionData s1, SubmissionData s2) {
				return s1.justification.toString().compareTo(
						s2.justification.toString());
			}
		});
	}

	/**
	 * Sorts submissions based on reviewer name then by email
	 * 
	 * @param submissions
	 */
	protected void sortSubmissionsByReviewer(List<SubmissionData> submissions) {
		Collections.sort(submissions, new Comparator<SubmissionData>() {
			public int compare(SubmissionData s1, SubmissionData s2) {
				int result = s1.reviewerName.compareTo(s2.reviewerName);
				if (result == 0)
					s1.reviewer.compareTo(s2.reviewer);
				return result;
			}
		});
	}

	/**
	 * Sorts submissions based on reviewee name then by email
	 * 
	 * @param submissions
	 */
	protected void sortSubmissionsByReviewee(List<SubmissionData> submissions) {
		Collections.sort(submissions, new Comparator<SubmissionData>() {
			public int compare(SubmissionData s1, SubmissionData s2) {
				int result = s1.revieweeName.compareTo(s2.revieweeName);
				if (result == 0)
					s1.reviewee.compareTo(s2.reviewee);
				return result;
			}
		});
	}

	/**
	 * Sorts submissions based on points (not the normalized one, although the
	 * relative ordering should be the same)
	 * 
	 * @param submissions
	 */
	protected void sortSubmissionsByPoints(List<SubmissionData> submissions) {
		Collections.sort(submissions, new Comparator<SubmissionData>() {
			public int compare(SubmissionData s1, SubmissionData s2) {
				return Integer.valueOf(s1.points).compareTo(
						Integer.valueOf(s2.points));
			}
		});
	}
	
	protected String printRequestParameters(HttpServletRequest request) {
		String requestParameters = "{";
		for (Enumeration f = request.getParameterNames(); f.hasMoreElements();) {
			String paramet = new String(f.nextElement().toString());
			requestParameters += paramet + ":" + request.getParameter(paramet) + ", ";
		}
		if (requestParameters != "{") {
			requestParameters = requestParameters.substring(0, requestParameters.length() - 2);
		}
		requestParameters += "}";
		return requestParameters;
	}
}
